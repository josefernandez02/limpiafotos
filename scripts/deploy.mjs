// Publishes dist/ as a single-commit orphan gh-pages branch, so the repo
// never accumulates old copies of the model.
import { execFileSync } from 'node:child_process';

const run = (args, cwd = 'dist') => execFileSync('git', args, { cwd, stdio: 'inherit' });
const remote = execFileSync('git', ['remote', 'get-url', 'origin'], { encoding: 'utf8' }).trim();
const author = ['-c', 'user.name=limpiafotos', '-c', 'user.email=limpiafotos@users.noreply.github.com'];

run(['init', '-q', '-b', 'gh-pages']);
run(['add', '-A']);
run([...author, 'commit', '-q', '-m', 'Publicar']);
run(['push', '-f', remote, 'gh-pages']);
console.log('Publicado en gh-pages');
