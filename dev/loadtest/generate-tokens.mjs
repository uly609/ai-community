#!/usr/bin/env node

import { fileURLToPath } from "node:url";

const config = {
  baseUrl: process.env.BASE_URL || "http://localhost:8080",
  count: Number(process.env.USER_COUNT || 100),
  usernamePrefix: process.env.USER_PREFIX || "load_user_",
  password: process.env.USER_PASSWORD || "123456",
  output: process.env.TOKEN_OUTPUT || fileURLToPath(new URL("./tokens.csv", import.meta.url)),
};

async function postJson(path, body) {
  const response = await fetch(`${config.baseUrl}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });

  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`Invalid JSON from ${path}: ${text}`);
  }
}

function csvEscape(value) {
  return `"${String(value).replaceAll('"', '""')}"`;
}

const tokens = [];
const fs = await import("node:fs/promises");

async function writeTokens() {
  const csv = [
    "username,token",
    ...tokens.map((item) => `${csvEscape(item.username)},${csvEscape(item.token)}`),
  ].join("\n");

  await fs.writeFile(config.output, `${csv}\n`, "utf8");
}

for (let i = 1; i <= config.count; i += 1) {
  const username = `${config.usernamePrefix}${String(i).padStart(3, "0")}`;
  const user = { username, password: config.password };

  const registerResult = await postJson("/users/register", user);
  if (registerResult.code !== 200 && !String(registerResult.message || "").includes("已存在")) {
    throw new Error(`Register failed for ${username}: ${JSON.stringify(registerResult)}`);
  }

  const loginResult = await postJson("/users/login", user);
  if (loginResult.code !== 200 || !loginResult.data) {
    throw new Error(`Login failed for ${username}: ${JSON.stringify(loginResult)}`);
  }

  tokens.push({ username, token: loginResult.data });
  await writeTokens();
  console.log(`${i}/${config.count} ${username}`);
}

console.log(`Generated ${tokens.length} tokens: ${config.output}`);
