import { cpSync, existsSync, lstatSync, mkdirSync, mkdtempSync, realpathSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

/** Copy only test inputs; never traverse a deployment's env, data or Git files. */
export function createTestWorkspace(source: string) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "wxctx-test-")));
  const project = join(root, "project"), home = join(root, "home"), temporary = join(root, "tmp");
  for (const dir of [project, home, temporary]) mkdirSync(dir, { mode: 0o700 });
  for (const name of ["src", "scripts", "tests", "skills", ".env.example", "README.md", "package.json", "bunfig.toml"]) {
    const path = join(source, name);
    if (!existsSync(path)) continue;
    cpSync(path, join(project, name), { recursive: true, filter: entry => {
      if (lstatSync(entry).isSymbolicLink()) throw new Error(`Test input must not be a symlink: ${entry}`);
      return true;
    }});
  }
  const localNm = join(source, "node_modules");
  const hoistedNm = join(source, "..", "node_modules");
  const nmTarget = existsSync(localNm) ? localNm : existsSync(hoistedNm) ? hoistedNm : "";
  if (nmTarget) symlinkSync(realpathSync(nmTarget), join(project, "node_modules"), "dir");
  const token = randomUUID();
  writeFileSync(join(root, ".test-isolation.json"), JSON.stringify({ token, project }), { mode: 0o600 });
  // Do not inherit credentials, NODE_OPTIONS, BUN_OPTIONS, proxy settings or app paths.
  const env = { HOME: home, TMPDIR: temporary, TMP: temporary, TEMP: temporary,
    PATH: "/usr/bin:/bin", IGNORE_HOME_DOTENV: "true", WXCTX_TEST_ROOT: root, WXCTX_TEST_TOKEN: token };
  return { root, project, env };
}
