import { describe, expect, test } from 'bun:test';
import crypto from 'node:crypto';
import vector from '../test-vectors/secure-v2.json';
import { establishSecureSession, generateSecureEphemeral, hkdfSha256, phoneStart, secureEqualHex, SECURE_MAX_INNER_BYTES, type SecureHello, type SecureStart } from '../src/secure-session.js';
import { encodeJsonRpcFrame } from '../src/framing.js';

const hello = vector.hello as SecureHello;
const start = vector.start as SecureStart;
const inner = Buffer.from(vector.innerHex, 'hex');
const pair = () => ({
  mac: establishSecureSession('mac', hello, start, vector.pskHex, vector.macPrivateKeyHex),
  phone: establishSecureSession('phone', hello, start, vector.pskHex, vector.phonePrivateKeyHex),
});
describe('secure transport v2 primitives and record contract', () => {
  test('RFC5869 case1 and independent NIST GCM known-answer', () => {
    expect(hkdfSha256(Buffer.alloc(22, 0x0b),Buffer.from('000102030405060708090a0b0c','hex'),Buffer.from('f0f1f2f3f4f5f6f7f8f9','hex'),42).toString('hex'))
      .toBe('3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865');
    const c=crypto.createCipheriv('aes-256-gcm',Buffer.alloc(32),Buffer.alloc(12));
    expect(Buffer.concat([c.update(Buffer.alloc(16)),c.final()]).toString('hex')).toBe('cea7403d4d606b6e074ec5d3baf39d18');
    expect(c.getAuthTag().toString('hex')).toBe('d0d1c8a799996bf0265b98b5d48ab919');
  });
  test('frozen cross-runtime key, proof, and bidirectional ciphertext vector', () => {
    const {mac,phone}=pair();
    expect(mac.pairSecretHex).toBe(vector.pairSecretHex);
    expect(phone.pairSecretHex).toBe(vector.pairSecretHex);
    expect(mac.macProofHex).toBe(vector.macProofHex);
    expect(phoneStart(hello,'pair','phone_test',vector.pskHex,{privateKeyHex:vector.phonePrivateKeyHex,publicKeyHex:start.publicKeyHex},start.noncePhoneHex)).toEqual(start);
    const m=mac.session.seal(inner),p=phone.session.seal(inner);
    expect(Buffer.from(m).toString('hex')).toBe(vector.macRecordHex);
    expect(Buffer.from(p).toString('hex')).toBe(vector.phoneRecordHex);
    expect(Buffer.from(phone.session.open(m))).toEqual(inner);
    expect(Buffer.from(mac.session.open(p))).toEqual(inner);
    expect(Buffer.from(mac.session.seal(inner))).not.toEqual(Buffer.from(m));
  });
  test('fresh ephemeral sessions interoperate and produce distinct ciphertext', () => {
    const m=generateSecureEphemeral(),p=generateSecureEphemeral();
    const h={...hello,publicKeyHex:m.publicKeyHex};
    const s=phoneStart(h,'auth','phone_test',vector.pskHex,p);
    const host=establishSecureSession('mac',h,s,vector.pskHex,m.privateKeyHex);
    const client=establishSecureSession('phone',h,s,vector.pskHex,p.privateKeyHex);
    const wire=host.session.seal(inner);
    expect(Buffer.from(client.session.open(wire))).toEqual(inner);
    expect(Buffer.from(wire).toString('hex')).not.toBe(vector.macRecordHex);
  });
  test('incorrect PSK, identity, mode, nonce, point, private key and malformed proof fail', () => {
    const attempts = [
      ()=>establishSecureSession('mac',hello,start,'44'.repeat(32),vector.macPrivateKeyHex),
      ()=>establishSecureSession('mac',{...hello,macId:'mac_other'},start,vector.pskHex,vector.macPrivateKeyHex),
      ()=>establishSecureSession('mac',hello,{...start,mode:'auth'},vector.pskHex,vector.macPrivateKeyHex),
      ()=>establishSecureSession('mac',{...hello,nonceMacHex:'44'.repeat(32)},start,vector.pskHex,vector.macPrivateKeyHex),
      ()=>establishSecureSession('mac',hello,{...start,publicKeyHex:'04'+'00'.repeat(64)},vector.pskHex,vector.macPrivateKeyHex),
      ()=>establishSecureSession('mac',hello,start,vector.pskHex,vector.phonePrivateKeyHex),
      ()=>establishSecureSession('mac',hello,{...start,proofHex:'bad'},vector.pskHex,vector.macPrivateKeyHex),
    ];
    for(const attempt of attempts) expect(attempt).toThrow();
    expect(secureEqualHex(vector.macProofHex,vector.macProofHex.toUpperCase())).toBe(false);
  });
  test('tampering each header/sequence/ciphertext/tag class closes the context', () => {
    for(const offset of [0,1,2,3,7,8,15,16,25,Buffer.from(vector.macRecordHex,'hex').length-1]) {
      const {phone}=pair(); const wire=Buffer.from(vector.macRecordHex,'hex');wire[offset]^=1;
      expect(()=>phone.session.open(wire)).toThrow();
      expect(()=>phone.session.open(Buffer.from(vector.macRecordHex,'hex'))).toThrow('closed');
    }
  });
  test('replay, skipped sequence, reflection and unrelated session rejected', () => {
    let {mac,phone}=pair(); const first=mac.session.seal(inner);phone.session.open(first);
    expect(()=>phone.session.open(first)).toThrow();
    ({mac,phone}=pair());mac.session.seal(inner);
    expect(()=>phone.session.open(mac.session.seal(inner))).toThrow();
    ({mac,phone}=pair());expect(()=>mac.session.open(mac.session.seal(inner))).toThrow();
    const freshMac=generateSecureEphemeral(),freshPhone=generateSecureEphemeral();
    const h={...hello,publicKeyHex:freshMac.publicKeyHex};
    const s=phoneStart(h,'pair','phone_test',vector.pskHex,freshPhone);
    const other=establishSecureSession('phone',h,s,vector.pskHex,freshPhone.privateKeyHex);
    expect(()=>other.session.open(Buffer.from(vector.macRecordHex,'hex'))).toThrow();
  });
  test('malformed/nested/truncated/overlong and plaintext records rejected', () => {
    for(const candidate of [Buffer.alloc(0),Buffer.from([0x4f,1,0x7e,0,0,0,0,0]),Buffer.concat([inner,Buffer.from([0])]),Buffer.alloc(SECURE_MAX_INNER_BYTES+1)]) {
      expect(()=>pair().mac.session.seal(candidate)).toThrow();
    }
    expect(()=>pair().phone.session.open(inner)).toThrow();
    expect(()=>pair().phone.session.open(Buffer.from(vector.macRecordHex,'hex').subarray(0,-1))).toThrow();
  });
  test('key usage limits and destroy forbid further traffic', () => {
    const {mac,phone}=pair();
    // Boundary injection avoids allocating 1 GiB or doing one million encryptions.
    (mac.session as any).sendSequence=2**20;
    expect(()=>mac.session.seal(inner)).toThrow('limit');
    (phone.session as any).receivedBytes=1024**3;
    expect(()=>phone.session.open(Buffer.from(vector.macRecordHex,'hex'))).toThrow('limit');
    const closed=pair();closed.mac.session.destroy();
    expect(()=>closed.mac.session.seal(encodeJsonRpcFrame({jsonrpc:'2.0',id:'x',result:{}}))).toThrow('closed');
  });
});
