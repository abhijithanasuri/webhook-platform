import { spawn } from 'node:child_process';
import { access, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import net from 'node:net';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const java = path.join(root, '.tools/java/jdk25.0.5_0/bin/java.exe');
const emulator = path.join(root, '.tools/dynamodb/DynamoDBLocal.jar');
try { await access(emulator); }
catch { throw new Error('DynamoDB Local test tool is missing. See docs/11-account-management.md for the official download.'); }
const reservation = net.createServer();
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
const port = reservation.address().port;
await new Promise(resolve => reservation.close(resolve));
const db = spawn(java, [`-Djava.library.path=${path.join(root, '.tools/dynamodb/DynamoDBLocal_lib')}`,
  '-jar', emulator, '-inMemory', '-sharedDb', '-port', String(port)], { cwd: root, stdio: ['ignore', 'ignore', 'pipe'], windowsHide: true });
let startupError = '';
db.stderr.on('data', data => { startupError = (startupError + data).slice(-2000); });
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (db.exitCode !== null) throw new Error('DynamoDB Local exited: ' + startupError);
    ready = await new Promise(resolve => {
      const connection = net.createConnection({ host: '127.0.0.1', port });
      connection.setTimeout(300);
      connection.once('connect', () => { connection.destroy(); resolve(true); });
      connection.once('error', () => { connection.destroy(); resolve(false); });
      connection.once('timeout', () => { connection.destroy(); resolve(false); });
    });
    if (ready) break;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  if (!ready) throw new Error('DynamoDB Local readiness timed out');
  const maven = path.join(root, '.tools/maven/apache-maven-3.9.16');
  const boot = (await readdir(path.join(maven, 'boot'))).find(file => /^plexus-classworlds-.*\.jar$/.test(file));
  if (!boot) throw new Error('Maven launcher jar is missing');
  const build = spawn(java, [`-Dmaven.home=${maven}`, `-Dclassworlds.conf=${path.join(maven, 'bin/m2.conf')}`,
    `-Dmaven.multiModuleProjectDirectory=${path.join(root, 'backend')}`, '-cp', path.join(maven, 'boot', boot),
    'org.codehaus.plexus.classworlds.launcher.Launcher', '-B', '-ntp',
    `-Dmaven.repo.local=${path.join(root, '.local/maven-repository')}`,
    `-Ddynamodb.test.endpoint=http://127.0.0.1:${port}`, '-f', path.join(root, 'backend/pom.xml'), 'verify'],
    { cwd: root, stdio: 'inherit', windowsHide: true });
  const code = await new Promise((resolve, reject) => { build.once('exit', resolve); build.once('error', reject); });
  process.exitCode = code === 0 ? 0 : 1;
} finally {
  if (db.exitCode === null) {
    const exited = new Promise(resolve => db.once('exit', resolve));
    db.kill();
    await exited;
  }
  console.log('Temporary in-memory DynamoDB test engine stopped.');
}
