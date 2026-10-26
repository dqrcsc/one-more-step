/**
 * Shared Verification Code (OTP) Extraction & Anti-Noise Filtering Engine
 * Used by Mac Host Daemon (`omsd`) and mirrored in Android `OtpExtractor.kt`.
 */

export interface ExtractedOtpResult {
  code: string;
  sender?: string;
  fullText: string;
}

const GENERIC_SENDER_TITLES = new Set([
  "短信",
  "信息",
  "新消息",
  "消息",
  "通知",
  "messages",
  "message",
  "sms",
  "mms",
  "com.android.mms",
  "com.google.android.apps.messaging",
]);

const TRIGGER_KEYWORD_REGEX =
  /(?:验证码|校验码|动态码|确认码|安全码|登录码|认证码|提取码|动态密码|随机码|口令码|验证代码|通行码|\b(?:verification\s*code|security\s*code|auth(?:entication)?\s*code|login\s*code|one[-\s]*time\s*(?:password|code)|passcode|otp|pin\s*code)\b|\bcode\s*(?:is|:)\b|\bis\s+your\s+(?:[\w-]+\s+)?code\b)/i;

const EXCLUDED_ALPHA_WORDS = new Set([
  "code",
  "your",
  "from",
  "with",
  "this",
  "that",
  "have",
  "will",
  "http",
  "https",
  "html",
  "info",
  "text",
  "user",
  "pass",
  "true",
  "false",
  "null",
]);

function isValidOtpCandidate(raw: string): boolean {
  if (!raw || raw.length < 4 || raw.length > 8) return false;
  if (EXCLUDED_ALPHA_WORDS.has(raw.toLowerCase())) return false;
  // Must contain at least one digit (pure digits or alphanumeric with >=2 digits)
  const digitCount = (raw.match(/\d/g) || []).length;
  if (/^\d{4,8}$/.test(raw)) return true;
  return digitCount >= 2 && /^[0-9A-Za-z]{4,8}$/.test(raw);
}

export function extractSenderSignature(
  text: string,
  senderHint?: string
): string | undefined {
  // 1. Check Chinese bracket signature 【...】 at start or end or anywhere in text
  const cnBracket = text.match(/【([^【】]{1,24})】/);
  if (cnBracket && cnBracket[1].trim()) {
    return cnBracket[1].trim();
  }

  // 2. Check ASCII bracket signature [...] at start or end
  const startAscii = text.match(/^\s*\[([^\[\]]{2,24})\]/);
  if (startAscii && startAscii[1].trim()) {
    return startAscii[1].trim();
  }
  const endAscii = text.match(/\[([^\[\]]{2,24})\]\s*$/);
  if (endAscii && endAscii[1].trim()) {
    return endAscii[1].trim();
  }

  // 3. Fallback to senderHint if meaningful
  if (senderHint) {
    const cleaned = senderHint.trim().replace(/^【|】$/g, "");
    if (
      cleaned &&
      !GENERIC_SENDER_TITLES.has(cleaned.toLowerCase()) &&
      cleaned.length <= 32
    ) {
      return cleaned;
    }
  }
  return undefined;
}

function maskInterferingNumbers(text: string): string {
  return (
    text
      // Mask URLs
      .replace(/https?:\/\/\S+/gi, " ")
      // Mask bracketed brand names that might contain digits like 【12306】 or 【58同城】
      .replace(/【[^【】]{1,24}】/g, " ")
      // Mask tail / account / order numbers (e.g. 尾号1234, 账户8899, 订单号202601)
      .replace(
        /(?:尾号|末四位|后四位|尾数|卡号|账号|账户|订单号|流水号|工单号|序列号|编号|手机尾号|银行卡)(?:为|是|:|：|\s)*[0-9A-Za-z]{3,20}/gi,
        " "
      )
      // Mask phone numbers & service hotlines
      .replace(/(?<!\d)1[3-9]\d{9}(?!\d)/g, " ")
      .replace(/(?<!\d)(?:400|800)[-\s]?\d{3}[-\s]?\d{4}(?!\d)/g, " ")
      .replace(/(?<!\d)\d{3,4}-\d{7,8}(?!\d)/g, " ")
      // Mask dates and times
      .replace(/20[2-3]\d\s*年(?:\s*\d{1,2}\s*月(?:\s*\d{1,2}\s*日)?)?/g, " ")
      .replace(/20[2-3]\d[-/.]\d{1,2}[-/.]\d{1,2}/g, " ")
      .replace(/\b\d{1,2}:\d{2}(?::\d{2})?\b/g, " ")
      // Mask durations (e.g. 5分钟, 10 mins, 60秒, 24小时)
      .replace(
        /\d+\s*(?:分钟|秒钟?|小时|天|个?工作日|min|mins|minutes|sec|secs|seconds|hr|hrs|hours)\b/gi,
        " "
      )
      // Mask money / currency amounts
      .replace(
        /(?:人民币|金额|消费|支出|转出|存入|余额|合计|总计|RMB|USD|CNY|\$|¥|￥)\s*\d+(?:\.\d+)?/gi,
        " "
      )
      .replace(/\d+(?:\.\d+)?\s*(?:元|块|万元|美元|港币)/g, " ")
  );
}

export function extractOtpFromText(
  rawText: string,
  senderHint?: string
): ExtractedOtpResult | null {
  if (!rawText) return null;
  const fullText = rawText.trim();
  if (fullText.length < 4 || fullText.length > 600) return null;

  const combinedForTrigger = `${senderHint || ""} ${fullText}`;
  if (!TRIGGER_KEYWORD_REGEX.test(combinedForTrigger)) {
    return null;
  }

  const sender = extractSenderSignature(fullText, senderHint);
  const masked = maskInterferingNumbers(fullText);

  // 1. Forward proximity: keyword followed closely by 4-8 digit/alphanumeric code
  const forwardRegex =
    /(?:验证码|校验码|动态码|确认码|安全码|登录码|认证码|提取码|动态密码|随机码|口令码|验证代码|通行码|verification\s*code|security\s*code|auth(?:entication)?\s*code|login\s*code|one[-\s]*time\s*(?:password|code)|passcode|otp|pin\s*code|\bcode\b)[^0-9A-Za-z]{0,18}(?<![0-9A-Za-z])([0-9]{4,8}|[0-9A-Za-z]{4,8})(?![0-9A-Za-z])/gi;

  let match: RegExpExecArray | null;
  while ((match = forwardRegex.exec(masked)) !== null) {
    const candidate = match[1];
    if (isValidOtpCandidate(candidate)) {
      return { code: candidate, sender, fullText };
    }
  }

  // 2. Backward proximity: 4-8 digit/alphanumeric code followed closely by keyword
  const backwardRegex =
    /(?<![0-9A-Za-z])([0-9]{4,8}|[0-9A-Za-z]{4,8})(?![0-9A-Za-z])[^0-9A-Za-z]{0,14}(?:是您的|为您的|是您的验证码|为验证码|验证码|校验码|动态码|确认码|安全码|is\s+your\s+(?:[\w-]+\s+)?(?:code|otp|passcode))/gi;

  while ((match = backwardRegex.exec(masked)) !== null) {
    const candidate = match[1];
    if (isValidOtpCandidate(candidate)) {
      return { code: candidate, sender, fullText };
    }
  }

  // 3. Fallback: standalone 4-8 pure digits in masked text (prefer 6 digits, then 4 digits, then others)
  const pureDigitsMatches = Array.from(
    masked.matchAll(/(?<!\d)(\d{4,8})(?!\d)/g),
    (m) => m[1]
  );
  if (pureDigitsMatches.length > 0) {
    const preferred =
      pureDigitsMatches.find((d) => d.length === 6) ||
      pureDigitsMatches.find((d) => d.length === 4) ||
      pureDigitsMatches[0];
    return { code: preferred, sender, fullText };
  }

  return null;
}
