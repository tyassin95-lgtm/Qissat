const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const os = require('os');
const http = require('http'), fs = require('fs'), path = require('path');
const FIX = path.join(__dirname, 'fixtures');
const shield = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/shield.js'), 'utf8')
  .replace('__QSH_NONCE__', 'testnonce').replace('__QSH_BRIDGE__', 'bridge.qissat.invalid');
// A 4-minute seekable test video, generated once (needs ffmpeg).
const CLIP = path.join(os.tmpdir(), 'qissat-shield-clip.webm');
if (!fs.existsSync(CLIP)) {
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc=duration=240:size=320x180:rate=5',
    '-f', 'lavfi', '-i', 'sine=frequency=440:duration=240', '-c:v', 'libvpx', '-b:v', '100k', '-c:a', 'libvorbis', '-shortest', CLIP]);
}
const srv = http.createServer((q, r) => {
  const name = q.url.split('?')[0].replace(/^\//, '') || 'top.html';
  const f = name === 'clip.webm' ? CLIP : path.join(FIX, name);
  if (!fs.existsSync(f)) { r.writeHead(404); return r.end(); }
  const t = f.endsWith('.html') ? 'text/html' : f.endsWith('.webm') ? 'video/webm' : 'text/plain';
  const size = fs.statSync(f).size;
  const m = /bytes=(\d*)-(\d*)/.exec(q.headers.range || '');
  if (m) {
    const a = m[1] ? parseInt(m[1]) : 0, b = m[2] ? parseInt(m[2]) : size - 1;
    r.writeHead(206, { 'content-type': t, 'accept-ranges': 'bytes', 'content-range': `bytes ${a}-${b}/${size}`, 'content-length': b - a + 1 });
    return fs.createReadStream(f, { start: a, end: b }).pipe(r);
  }
  r.writeHead(200, { 'content-type': t, 'accept-ranges': 'bytes', 'content-length': size });
  fs.createReadStream(f).pipe(r);
}).listen(8765);
let pass = 0, fail = 0;
const check = (name, ok, extra) => { (ok ? pass++ : fail++); console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? '  ' + extra : '')); };
(async () => {
  const browser = await chromium.launch({ args: ['--host-resolver-rules=MAP qisat.tv 127.0.0.1, MAP player.local 127.0.0.1, MAP embed.local 127.0.0.1', '--autoplay-policy=no-user-gesture-required'] });
  const ctx = await browser.newContext({ viewport: { width: 800, height: 450 } });
  await ctx.addInitScript(shield);
  const page = await ctx.newPage();
  const popups = []; ctx.on('page', p => popups.push('new page ' + p.url()));
  const beacons = [];
  await page.route('https://bridge.qissat.invalid/**', r => { beacons.push(r.request().url()); r.fulfill({ status: 204 }); });
  await page.goto('http://qisat.tv:8765/top.html');   // hostname qisat.tv => first party
  await page.waitForTimeout(1500);

  check('window.open returns a fake window, no real popup', await page.evaluate(() => window.__results.openFake) && popups.length === 0);
  // off-site target=_blank link in the first party page: left to native layer, but must not open a window here
  await page.click('#internal');
  await page.waitForTimeout(500);
  check('first-party target=_blank same-site link stays in the same view', page.url().endsWith('/other.html') && popups.length === 0, page.url());
  await page.goBack(); await page.waitForTimeout(800);

  const embed = page.frames().find(f => f.url().includes('embed.html'));
  check('nested embed frame loaded', !!embed);
  check('anti-adblock bait globals defined in third-party frame', JSON.stringify(await embed.evaluate(() => window.__bait)) === '[true,0]', JSON.stringify(await embed.evaluate(() => window.__bait)));
  await page.waitForTimeout(800);
  check('ghost overlay (transparent, fixed, full-screen) removed', await embed.evaluate(() => !document.getElementById('ghost')));
  check('invisible full-screen link overlay removed', await embed.evaluate(() => !document.getElementById('ghostlink')));
  check('legit visible overlay kept', await embed.evaluate(() => !!document.getElementById('legit')));

  // click inside the embed: page script calls window.open
  await embed.evaluate(() => document.getElementById('legit').remove());
  await page.mouse.click(300, 200);
  await page.waitForTimeout(500);
  check('click-triggered window.open in ad script is neutralised', popups.length === 0 && (await embed.evaluate(() => window.__events.join(','))).includes('open:fake'), String(popups));

  // media bridge + beacon
  await embed.evaluate(() => document.getElementById('v').play());
  await page.waitForTimeout(1200);
  const ok = beacons.some(b => /[?&]k=testnonce/.test(b) && /[?&]e=play/.test(b));
  check('play beacon sent with nonce', ok, beacons[0] ? beacons[0].slice(0, 160) : 'none');
  const paused = () => embed.evaluate(() => document.getElementById('v').paused);
  check('video is playing', !(await paused()));
  await page.evaluate(() => window.__qshCmd('pause'));
  await page.waitForTimeout(500);
  check('pause command relayed top -> wrapper -> embed', await paused());
  await page.evaluate(() => window.__qshCmd('play'));
  await page.waitForTimeout(500);
  check('play command relayed', !(await paused()));
  const t0 = await embed.evaluate(() => document.getElementById('v').currentTime);
  await page.evaluate(() => window.__qshCmd('seek', 60));
  await page.waitForTimeout(500);
  const t1 = await embed.evaluate(() => document.getElementById('v').currentTime);
  check('seek +60 relayed', t1 - t0 > 50, `t0=${t0.toFixed(1)} t1=${t1.toFixed(1)}`);
  await page.evaluate(() => window.__qshCmd('mute'));
  await page.waitForTimeout(200);
  check('mute toggled', !(await embed.evaluate(() => document.getElementById('v').muted)));  // started muted -> now unmuted
  await page.evaluate(() => window.__qshCmd('toggle'));
  await page.waitForTimeout(400);
  check('toggle pauses', await paused());

  // forged beacon (no nonce) must carry no authority: the app rejects it, shield never creates one
  check('only one kind of beacon (play) produced', beacons.every(b => /e=play/.test(b)));
  check('no popups at all', popups.length === 0, String(popups));
  console.log(`\n${pass} passed, ${fail} failed`);
  await browser.close(); srv.close();
  process.exit(fail ? 1 : 0);
})();
