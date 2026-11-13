import { build } from 'esbuild';
await build({
  entryPoints: ['pages-gateway/entry.ts'], outfile: 'pages-gateway/public/_worker.js',
  bundle: true, format: 'esm', platform: 'browser', target: 'es2022',
});
