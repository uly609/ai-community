#!/usr/bin/env node

import fs from "node:fs";

const resultPath = process.argv[2];
if (!resultPath) {
  console.error("Usage: node calculate-metrics.mjs <jmeter-result.jtl>");
  process.exit(1);
}

function parseCsvLine(line) {
  const fields = [];
  let value = "";
  let quoted = false;
  for (let index = 0; index < line.length; index += 1) {
    const character = line[index];
    if (character === '"') {
      if (quoted && line[index + 1] === '"') {
        value += '"';
        index += 1;
      } else {
        quoted = !quoted;
      }
    } else if (character === "," && !quoted) {
      fields.push(value);
      value = "";
    } else {
      value += character;
    }
  }
  fields.push(value);
  return fields;
}

const lines = fs.readFileSync(resultPath, "utf8").trim().split(/\r?\n/);
const header = parseCsvLine(lines.shift());
const indexOf = (name) => header.indexOf(name);
const timestampIndex = indexOf("timeStamp");
const elapsedIndex = indexOf("elapsed");
const successIndex = indexOf("success");

if ([timestampIndex, elapsedIndex, successIndex].some((index) => index < 0)) {
  throw new Error(`JTL 缺少指标列，实际表头为: ${header.join(", ")}`);
}

const samples = lines.filter(Boolean).map(parseCsvLine);
const elapsed = samples.map((row) => Number(row[elapsedIndex])).sort((left, right) => left - right);
const successCount = samples.filter((row) => row[successIndex] === "true").length;
const startTime = Math.min(...samples.map((row) => Number(row[timestampIndex])));
const endTime = Math.max(...samples.map((row) => Number(row[timestampIndex]) + Number(row[elapsedIndex])));
const durationSeconds = Math.max((endTime - startTime) / 1000, 0.001);
const percentile = (fraction) => elapsed[Math.min(elapsed.length - 1, Math.ceil(elapsed.length * fraction) - 1)];
const average = elapsed.reduce((sum, value) => sum + value, 0) / elapsed.length;

console.log(JSON.stringify({
  totalRequests: samples.length,
  successfulRequests: successCount,
  failedRequests: samples.length - successCount,
  errorRate: `${(((samples.length - successCount) / samples.length) * 100).toFixed(2)}%`,
  averageRtMs: Number(average.toFixed(2)),
  p95RtMs: percentile(0.95),
  maxRtMs: elapsed.at(-1),
  throughputQps: Number((samples.length / durationSeconds).toFixed(2)),
  effectiveTps: Number((successCount / durationSeconds).toFixed(2)),
  testWindowSeconds: Number(durationSeconds.toFixed(3)),
}, null, 2));
