import { copyFile, mkdir, unlink, readFile, writeFile } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import net from 'node:net';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const port = process.env.WEBHOOK_LOCAL_API_PORT || '8080';
if (!/^\d+$/.test(port) || Number(port) < 1024 || Number(port) > 65535) throw new Error('Invalid local API port');
await new Promise((resolve, reject) => {
  const probe = net.createServer();
  probe.once('error', error => reject(new Error(error.code === 'EADDRINUSE'
    ? `Port ${port} is already in use. Stop the existing backend before restarting. No process was stopped.`
    : `Cannot check local API port (${error.code}).`)));
  probe.listen(Number(port), '127.0.0.1', () => probe.close(resolve));
});
const directory = path.join(root, '.local', 'runtime');
await mkdir(directory, { recursive: true });
const runtimeJar = path.join(directory, `webhook-api-${randomUUID()}.jar`);
await copyFile(path.join(root, 'backend/api/target/webhook-api-0.1.0-SNAPSHOT.jar'), runtimeJar);
const build = createHash('sha256').update(await readFile(runtimeJar)).digest('hex').slice(0, 12);
console.log(`Starting local API build ${build} on 127.0.0.1:${port}.`);
const java = path.join(root, '.tools/java/jdk25.0.5_0/bin/java.exe');
const keyFile = path.join(root, '.local', 'endpoint-signing-key');
try { await writeFile(keyFile, randomBytes(32).toString('base64'), { flag: 'wx', mode: 0o600 }); }
catch (error) { if (error.code !== 'EEXIST') throw error; }
const key = (await readFile(keyFile, 'utf8')).trim();
const child = spawn(java, ['-jar', runtimeJar, '--spring.profiles.active=local', `--server.port=${port}`], {
  stdio: 'inherit', env: { ...process.env, WEBHOOK_LOCAL_SIGNING_KEY: key },
});
// On Windows, the Java child shares this console and receives Ctrl+C itself.
process.on('SIGINT', () => { if (process.platform !== 'win32') child.kill('SIGINT'); });
child.on('error', async () => {
  await unlink(runtimeJar).catch(() => {});
  console.error('Could not start project-local Java.');
  process.exitCode = 1;
});
child.on('exit', async code => {
  await unlink(runtimeJar).catch(() => {});
  process.exitCode = code || 0;
});
