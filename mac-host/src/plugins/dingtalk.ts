import crypto from "node:crypto";

export interface DingTalkConfig {
  enabled: boolean;
  webhookUrl?: string;
  secret?: string;
}

/**
 * Module 06: Optional DingTalk Custom Robot Webhook Plugin (with HMAC-SHA256 Signing)
 */
export class DingTalkPlugin {
  private config: DingTalkConfig;

  constructor(config: DingTalkConfig = { enabled: false }) {
    this.config = config;
  }

  public updateConfig(config: DingTalkConfig): void {
    this.config = config;
  }

  public get isEnabled(): boolean {
    return Boolean(this.config.enabled && this.config.webhookUrl);
  }

  /**
   * Build signed DingTalk webhook URL using timestamp + "\n" + secret
   */
  public static buildSignedWebhookUrl(
    baseWebhookUrl: string,
    secret?: string,
    timestampMs = Date.now()
  ): string {
    if (!secret) return baseWebhookUrl;
    const stringToSign = `${timestampMs}\n${secret}`;
    const signBase64 = crypto
      .createHmac("sha256", secret)
      .update(stringToSign, "utf8")
      .digest("base64");
    const url = new URL(baseWebhookUrl);
    url.searchParams.set("timestamp", String(timestampMs));
    url.searchParams.set("sign", signBase64);
    return url.toString();
  }

  public async sendMarkdownCard(title: string, markdownText: string): Promise<boolean> {
    if (!this.isEnabled || !this.config.webhookUrl) return false;
    try {
      const targetUrl = DingTalkPlugin.buildSignedWebhookUrl(
        this.config.webhookUrl,
        this.config.secret
      );
      const res = await fetch(targetUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          msgtype: "markdown",
          markdown: {
            title,
            text: `### ${title}\n\n${markdownText}`,
          },
        }),
      });
      const json = (await res.json()) as { errcode?: number };
      return json.errcode === 0;
    } catch {
      return false;
    }
  }
}
