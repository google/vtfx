/**
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Measures @xterm/headless with the workloads of vtfx-core's TerminalBenchmark, for comparing
// vtfx with xterm.js on the same machine (docs/decisions/0014). xterm.js in a WebView parses
// with the same code, without the cost of the WebView.
//
// Usage: node xterm-headless.mjs RECORDINGS_DIR [SECONDS]
// Run from a directory where @xterm/headless is installed (scripts/benchmark.py does this).

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(path.join(process.cwd(), 'package.json'));
const { Terminal } = require('@xterm/headless');

const CHUNK = 4096;
const recordings = process.argv[2];
const seconds = Number(process.argv[3] ?? 3);
const encoder = new TextEncoder();

function ascii() {
  let s = '';
  for (let i = 0; i < 20000; i++) s += `${String(i).padStart(6)}  fun example(value: Int): Int = value * ${i} + 42 // comment\r\n`;
  return encoder.encode(s);
}

function colored() {
  let s = '';
  for (let i = 0; i < 20000; i++) {
    s += `\x1b[1;3${i % 8}mdir${i}\x1b[0m  \x1b[38;5;${i % 256}mfile.txt\x1b[0m  `;
    s += `\x1b[48;2;${i % 256};64;128m bg \x1b[49m \x1b[4mlink\x1b[24m\r\n`;
  }
  return encoder.encode(s);
}

function cjk() {
  let s = '';
  for (let i = 0; i < 20000; i++) s += `${i}: 日本語のテキスト、全角の文字が二つのセルを占める。かなカナ漢字\r\n`;
  return encoder.encode(s);
}

function replay() {
  const files = fs.readdirSync(recordings).filter((f) => f.endsWith('.out')).sort();
  return Buffer.concat(files.map((f) => fs.readFileSync(path.join(recordings, f))));
}

function writeAll(term, data) {
  return new Promise((resolve) => {
    for (let offset = 0; offset < data.length; offset += CHUNK) {
      const chunk = data.subarray(offset, Math.min(offset + CHUNK, data.length));
      if (offset + CHUNK >= data.length) term.write(chunk, resolve);
      else term.write(chunk);
    }
  });
}

async function run(data, secs) {
  const term = new Terminal({ cols: 120, rows: 40, allowProposedApi: true, scrollback: 10000 });
  const start = performance.now();
  let bytes = 0;
  while (performance.now() - start < secs * 1000) {
    await writeAll(term, data);
    bytes += data.length;
  }
  const rate = bytes / ((performance.now() - start) / 1000) / 1e6;
  term.dispose();
  return rate;
}

const workloads = [['ascii', ascii()], ['sgr', colored()], ['cjk', cjk()], ['recordings', replay()]];
console.log('workload\tMB/s');
for (const [name, data] of workloads) {
  await run(data, seconds / 3);
  console.log(`${name}\t${(await run(data, seconds)).toFixed(1)}`);
}
