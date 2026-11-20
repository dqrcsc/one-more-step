import { existsSync, realpathSync, statSync } from "node:fs";
import path from "node:path";

const SAFE_RASTER_IMAGE_MIMES: Record<string, string> = {
  ".webp": "image/webp",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".bmp": "image/bmp",
  ".heic": "image/heic",
  ".heif": "image/heif",
  ".avif": "image/avif",
};

export interface ResolvedRelativeAsset {
  normalizedRelPath: string;
  realAbsPath: string;
  mimeType: string;
}

export function escapeReaderHtml(raw: string): string {
  return String(raw ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function normalizeRelativeAssetInput(rawPath: string): string | null {
  let cleaned = String(rawPath || "").trim();
  if (!cleaned) return null;
  if (cleaned.startsWith("<") && cleaned.endsWith(">")) {
    cleaned = cleaned.slice(1, -1).trim();
  }
  const spaceTitleIdx = cleaned.search(/\s+["']/);
  if (spaceTitleIdx > 0) {
    cleaned = cleaned.slice(0, spaceTitleIdx).trim();
  }
  try {
    cleaned = decodeURIComponent(cleaned);
  } catch {
    // keep raw if not URI-encoded
  }
  if (
    !cleaned ||
    cleaned.includes("\0") ||
    cleaned.includes("\\") ||
    cleaned.startsWith("/") ||
    cleaned.startsWith("~") ||
    /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(cleaned)
  ) {
    return null;
  }
  const normalized = path.posix.normalize(cleaned);
  if (
    !normalized ||
    normalized === "." ||
    normalized === ".." ||
    normalized.startsWith("../") ||
    normalized.startsWith("/")
  ) {
    return null;
  }
  return normalized;
}

/**
 * Strictly resolves a relative raster image path (`assets/...`) against the parent directory
 * of a Markdown file in the Vault, rejecting path traversal, symlinks escaping `baseDir`,
 * and active content formats (e.g. `.svg`, `.html`).
 */
export function resolveMarkdownRelativeAsset(
  docPath: string | null | undefined,
  rawRelPath: string
): ResolvedRelativeAsset | null {
  if (!docPath || !existsSync(docPath)) return null;
  const normalizedRelPath = normalizeRelativeAssetInput(rawRelPath);
  if (!normalizedRelPath) return null;

  const ext = path.extname(normalizedRelPath).toLowerCase();
  const mimeType = SAFE_RASTER_IMAGE_MIMES[ext];
  if (!mimeType) return null;

  try {
    const baseDir = realpathSync(path.dirname(docPath));
    const candidate = path.resolve(baseDir, normalizedRelPath);
    if (!existsSync(candidate)) return null;
    const st = statSync(candidate);
    if (!st.isFile() || st.size <= 0 || st.size > 35 * 1024 * 1024) return null;
    const realAbsPath = realpathSync(candidate);
    const prefix = baseDir.endsWith(path.sep) ? baseDir : baseDir + path.sep;
    if (!realAbsPath.startsWith(prefix)) return null;
    return {
      normalizedRelPath,
      realAbsPath,
      mimeType,
    };
  } catch {
    return null;
  }
}

export interface ReaderRenderOptions {
  markdown: string;
  docPath?: string | null;
  articleId?: number;
  turnId?: number;
  docKind: "note" | "source" | "summary" | "turn";
  assetUrlPrefix?: string;
  stripHeaderBlock?: boolean;
}

export interface ReaderRenderOutput {
  html: string;
  resolvedImages: Record<string, string>;
}

function sanitizeSafeHttpUrl(rawUrl: string): string | null {
  const trimmed = String(rawUrl || "").trim();
  if (!trimmed) return null;
  try {
    const u = new URL(trimmed);
    if (u.protocol !== "http:" && u.protocol !== "https:") return null;
    return u.toString();
  } catch {
    return null;
  }
}

function stripYamlFrontmatter(markdown: string): {
  body: string;
  frontmatter: Record<string, string>;
} {
  const normalized = String(markdown || "").replace(/\r\n/g, "\n");
  const frontmatter: Record<string, string> = {};
  if (!normalized.startsWith("---\n")) {
    return { body: normalized, frontmatter };
  }
  const endMatch = normalized.slice(4).match(/\n---(?:\n|$)/);
  if (!endMatch || endMatch.index === undefined) {
    return { body: normalized, frontmatter };
  }
  const yamlBlock = normalized.slice(4, 4 + endMatch.index);
  for (const line of yamlBlock.split("\n")) {
    const kv = line.match(/^([A-Za-z0-9_-]+)\s*:\s*(.+)$/);
    if (kv) {
      frontmatter[kv[1]] = kv[2].trim().replace(/^["']|["']$/g, "");
    }
  }
  const afterIdx = 4 + endMatch.index + endMatch[0].length;
  return {
    body: normalized.slice(afterIdx).replace(/^\n+/, ""),
    frontmatter,
  };
}

interface ExtractedTopHeader {
  body: string;
  chipsHtml: string;
}

/**
 * Compacts the verbose auto-generated top `# Title` + `- **Key**: Value` + `---` metadata block
 * in `_source.md` and `_note.md` so 300-char WeChat URLs and duplicate titles don't clutter reading.
 */
function compactVerboseTopHeader(body: string): ExtractedTopHeader {
  const lines = body.split("\n");
  let idx = 0;
  while (idx < lines.length && !lines[idx].trim()) idx++;

  if (idx >= lines.length || !/^#\s+.+$/.test(lines[idx].trim())) {
    return { body, chipsHtml: "" };
  }
  let scan = idx + 1;
  while (scan < lines.length && !lines[scan].trim()) scan++;

  const bulletLines: string[] = [];
  while (scan < lines.length) {
    const t = lines[scan].trim();
    if (/^-\s+\*\*[^*]+\*\*\s*[：:]/.test(t)) {
      bulletLines.push(t);
      scan++;
    } else if (!t && bulletLines.length > 0) {
      scan++;
      break;
    } else {
      break;
    }
  }

  while (scan < lines.length && !lines[scan].trim()) scan++;
  if (bulletLines.length === 0 || scan >= lines.length || !/^(-{3,}|\*{3,})$/.test(lines[scan].trim())) {
    return { body, chipsHtml: "" };
  }

  const chips: string[] = [];
  for (const b of bulletLines) {
    const m = b.match(/^-\s+\*\*([^*]+)\*\*\s*[：:]\s*(.+)$/);
    if (!m) continue;
    const key = m[1].trim();
    const val = m[2].trim();
    // Author & user annotation are already shown in the HUD/Web header bar
    if (key.includes("公众号") || key.includes("转发时批注")) {
      continue;
    }
    const mdLinkMatch = val.match(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/);
    if (key.includes("原始链接") || key.includes("原始材料")) {
      const safeHref = mdLinkMatch ? sanitizeSafeHttpUrl(mdLinkMatch[2]) : sanitizeSafeHttpUrl(val);
      if (safeHref) {
        chips.push(
          `<a class="wx-meta-chip wx-meta-link" href="${escapeReaderHtml(safeHref)}" target="_blank" rel="noopener noreferrer">🔗 查看微信原网页</a>`
        );
      }
      continue;
    }
    if (key.includes("发布时间")) {
      if (val && !val.includes("未知")) {
        chips.push(`<span class="wx-meta-chip">📅 发布 ${escapeReaderHtml(val)}</span>`);
      }
      continue;
    }
    if (key.includes("捕获时间")) {
      chips.push(`<span class="wx-meta-chip">📥 归档 ${escapeReaderHtml(val)}</span>`);
      continue;
    }
    if (key.includes("建议分诊")) {
      const cleanVal = val.replace(/`/g, "").trim();
      chips.push(`<span class="wx-meta-chip">🏷️ 分诊 ${escapeReaderHtml(cleanVal)}</span>`);
      continue;
    }
  }

  const remaining = lines.slice(scan + 1).join("\n").replace(/^\n+/, "");
  const chipsHtml =
    chips.length > 0 ? `<div class="wx-meta-strip">${chips.join("")}</div>` : "";
  return { body: remaining, chipsHtml };
}

export function renderWeChatMarkdownReaderHtml(opts: ReaderRenderOptions): ReaderRenderOutput {
  const resolvedImages: Record<string, string> = {};
  const assetPrefix = opts.assetUrlPrefix || "/api/wechat/asset";
  const { body: withoutFm } = stripYamlFrontmatter(opts.markdown || "");
  const { body, chipsHtml } =
    opts.stripHeaderBlock !== false && (opts.docKind === "source" || opts.docKind === "note")
      ? compactVerboseTopHeader(withoutFm)
      : { body: withoutFm, chipsHtml: "" };

  const buildImageTag = (altRaw: string, srcRaw: string, isBlockFigure: boolean): string => {
    const resolved = resolveMarkdownRelativeAsset(opts.docPath, srcRaw);
    const cleanAlt = altRaw.trim();
    const meaningfulCaption =
      cleanAlt && !/^(image|img|pic|photo|截图|图片|\d+)$/i.test(cleanAlt) ? cleanAlt : "";

    if (resolved) {
      const imgKey = `${opts.docKind}:${resolved.normalizedRelPath}`;
      resolvedImages[imgKey] = resolved.realAbsPath;
      resolvedImages[resolved.normalizedRelPath] = resolved.realAbsPath;

      const params = new URLSearchParams();
      if (opts.articleId && opts.articleId > 0) {
        params.set("articleId", String(opts.articleId));
        params.set("doc", opts.docKind === "note" ? "note" : "source");
      } else if (opts.turnId && opts.turnId > 0) {
        params.set("turnId", String(opts.turnId));
      }
      params.set("path", resolved.normalizedRelPath);
      const assetHref = `${assetPrefix}?${params.toString()}`;

      const imgHtml = `<img class="wx-inline-img" src="${escapeReaderHtml(assetHref)}" data-rel-path="${escapeReaderHtml(resolved.normalizedRelPath)}" data-img-key="${escapeReaderHtml(imgKey)}" alt="${escapeReaderHtml(cleanAlt || "文章配图")}" loading="lazy" title="点击放大查看高清原图" />`;
      if (!isBlockFigure) return imgHtml;
      return `<figure class="wx-figure">${imgHtml}${
        meaningfulCaption ? `<figcaption class="wx-figcaption">${escapeReaderHtml(meaningfulCaption)}</figcaption>` : ""
      }</figure>`;
    }

    const extUrl = sanitizeSafeHttpUrl(srcRaw);
    if (extUrl) {
      return `<a class="wx-ext-img-pill" href="${escapeReaderHtml(extUrl)}" target="_blank" rel="noopener noreferrer">🖼️ 查看外部原图${
        meaningfulCaption ? `：${escapeReaderHtml(meaningfulCaption)}` : ""
      }</a>`;
    }
    return `<span class="wx-missing-img">🖼️ 本地配图未找到 (${escapeReaderHtml(srcRaw.slice(0, 80))})</span>`;
  };

  const renderInline = (rawLine: string): string => {
    const tokens: string[] = [];
    const stash = (htmlFrag: string): string => {
      const id = `\u0000TK${tokens.length}\u0000`;
      tokens.push(htmlFrag);
      return id;
    };

    // 1. Inline code `...`
    let work = rawLine.replace(/`([^`\n]+)`/g, (_, code: string) =>
      stash(`<code class="wx-inline-code">${escapeReaderHtml(code)}</code>`)
    );

    // 2. Inline image ![alt](src)
    work = work.replace(/!\[([^\]]*)\]\(([^)\n]+)\)/g, (_, alt: string, src: string) =>
      stash(buildImageTag(alt, src, false))
    );

    // 3. Obsidian WikiLinks [[target|label]] or [[target]]
    work = work.replace(/\[\[([^\]|\n]+)(?:\|([^\]\n]+))?\]\]/g, (_, target: string, alias?: string) =>
      stash(`<span class="wx-wikilink">🔗 ${escapeReaderHtml((alias || target).trim())}</span>`)
    );

    // 4. Markdown links [label](url)
    work = work.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label: string, rawHref: string) => {
      const safeHref = sanitizeSafeHttpUrl(rawHref);
      if (!safeHref) return escapeReaderHtml(label);
      return stash(
        `<a class="wx-link" href="${escapeReaderHtml(safeHref)}" target="_blank" rel="noopener noreferrer">${escapeReaderHtml(label)}</a>`
      );
    });

    // Escape all remaining text before applying bold/italic/strikethrough
    let escaped = escapeReaderHtml(work);

    // Bold **...** (tolerant of CJK inner trailing/leading spaces)
    escaped = escaped.replace(/\*\*([^*\n]+?)\*\*/g, (_, inner: string) => {
      const trimmed = inner.trim();
      return trimmed ? `<strong>${trimmed}</strong>` : `**${inner}**`;
    });

    // Strikethrough ~~...~~
    escaped = escaped.replace(/~~([^~\n]+?)~~/g, "<del>$1</del>");

    // Italic *...*
    escaped = escaped.replace(/(^|[^*])\*([^*\n]+?)\*(?!\*)/g, "$1<em>$2</em>");

    // Restore stashed HTML tokens
    return escaped.replace(/\u0000TK(\d+)\u0000/g, (_, idxStr: string) => tokens[Number(idxStr)] || "");
  };

  const lines = body.split("\n");
  const blocks: string[] = [];
  if (chipsHtml) blocks.push(chipsHtml);

  let i = 0;
  while (i < lines.length) {
    const rawLine = lines[i];
    const trimmed = rawLine.trim();

    if (!trimmed) {
      i++;
      continue;
    }

    // Fenced code block
    const fenceMatch = trimmed.match(/^(`{3,}|~{3,})([^\s`~]*)/);
    if (fenceMatch) {
      const fence = fenceMatch[1];
      const lang = fenceMatch[2] || "";
      const codeLines: string[] = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith(fence)) {
        codeLines.push(lines[i]);
        i++;
      }
      if (i < lines.length) i++; // skip closing fence
      blocks.push(
        `<div class="wx-code-wrap">${
          lang ? `<div class="wx-code-lang">${escapeReaderHtml(lang)}</div>` : ""
        }<pre class="wx-code"><code>${escapeReaderHtml(codeLines.join("\n"))}</code></pre></div>`
      );
      continue;
    }

    // Standalone image line ![alt](src)
    const soloImgMatch = trimmed.match(/^!\[([^\]]*)\]\(([^)\n]+)\)$/);
    if (soloImgMatch) {
      blocks.push(buildImageTag(soloImgMatch[1], soloImgMatch[2], true));
      i++;
      continue;
    }

    // Horizontal rule
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      blocks.push("<hr class=\"wx-hr\" />");
      i++;
      continue;
    }

    // Headings
    const headingMatch = trimmed.match(/^(#{1,6})\s+(.+)$/);
    if (headingMatch) {
      const level = Math.min(6, headingMatch[1].length);
      blocks.push(`<h${level} class="wx-h${level}">${renderInline(headingMatch[2].trim())}</h${level}>`);
      i++;
      continue;
    }

    // Markdown table
    if (
      trimmed.includes("|") &&
      i + 1 < lines.length &&
      /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(lines[i + 1])
    ) {
      const parseCells = (rowStr: string): string[] =>
        rowStr
          .trim()
          .replace(/^\|/, "")
          .replace(/\|$/, "")
          .split("|")
          .map((c) => c.trim());
      const headers = parseCells(trimmed);
      i += 2; // skip header + separator
      const bodyRows: string[][] = [];
      while (i < lines.length && lines[i].trim().includes("|") && lines[i].trim() !== "") {
        bodyRows.push(parseCells(lines[i]));
        i++;
      }
      const thead = `<thead><tr>${headers.map((h) => `<th>${renderInline(h)}</th>`).join("")}</tr></thead>`;
      const tbody =
        bodyRows.length > 0
          ? `<tbody>${bodyRows
              .map((r) => `<tr>${headers.map((_, colIdx) => `<td>${renderInline(r[colIdx] ?? "")}</td>`).join("")}</tr>`)
              .join("")}</tbody>`
          : "";
      blocks.push(`<div class="wx-table-wrap"><table class="wx-table">${thead}${tbody}</table></div>`);
      continue;
    }

    // Blockquote
    if (trimmed.startsWith(">")) {
      const quoteLines: string[] = [];
      while (i < lines.length && lines[i].trim().startsWith(">")) {
        quoteLines.push(lines[i].trim().replace(/^>\s?/, ""));
        i++;
      }
      blocks.push(
        `<blockquote class="wx-quote">${quoteLines
          .map((ql) => (ql.trim() ? `<p>${renderInline(ql)}</p>` : ""))
          .filter(Boolean)
          .join("")}</blockquote>`
      );
      continue;
    }

    // Unordered or ordered list
    const isUl = /^[-*+]\s+/.test(trimmed);
    const isOl = /^\d+\.\s+/.test(trimmed);
    if (isUl || isOl) {
      const tag = isOl ? "ol" : "ul";
      const items: string[] = [];
      while (i < lines.length) {
        const lt = lines[i].trim();
        const mItem = isOl ? lt.match(/^\d+\.\s+(.+)$/) : lt.match(/^[-*+]\s+(.+)$/);
        if (!mItem) break;
        items.push(`<li>${renderInline(mItem[1])}</li>`);
        i++;
      }
      blocks.push(`<${tag} class="wx-list">${items.join("")}</${tag}>`);
      continue;
    }

    // Standalone short numeric section badge common in WeChat articles (e.g. "01", "02", "PART 01")
    if (
      /^(?:PART\s*)?0?[1-9]\d?$/i.test(trimmed) &&
      i + 1 < lines.length &&
      lines[i + 1].trim() === ""
    ) {
      blocks.push(`<div class="wx-sec-num">${escapeReaderHtml(trimmed)}</div>`);
      i++;
      continue;
    }

    // Standard paragraph (merge consecutive plain lines, stop before block constructs)
    const paraLines: string[] = [];
    while (i < lines.length) {
      const cur = lines[i].trim();
      if (
        !cur ||
        /^(`{3,}|~{3,})/.test(cur) ||
        /^!\[([^\]]*)\]\(([^)\n]+)\)$/.test(cur) ||
        /^(-{3,}|\*{3,}|_{3,})$/.test(cur) ||
        /^#{1,6}\s+/.test(cur) ||
        cur.startsWith(">") ||
        /^[-*+]\s+/.test(cur) ||
        /^\d+\.\s+/.test(cur)
      ) {
        break;
      }
      paraLines.push(renderInline(cur));
      i++;
    }
    if (paraLines.length > 0) {
      blocks.push(`<p class="wx-p">${paraLines.join("<br />")}</p>`);
    } else {
      i++;
    }
  }

  const html =
    blocks.length > 0
      ? `<article class="wx-rich-reader">${blocks.join("\n")}</article>`
      : `<div class="wx-empty-reader">（暂无正文内容）</div>`;

  return { html, resolvedImages };
}
