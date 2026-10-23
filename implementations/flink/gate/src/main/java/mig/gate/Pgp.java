package mig.gate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;

/**
 * OpenPGP decryption for the source system's delivery format — {@code ACCOUNT.tar.gz.pgp}
 *.
 *
 * <p>The real input arrives as {@code .tar.gz.pgp}, and until this class existed the
 * gate could not read it: {@code harness/bundle.py} said "PGP optional and not applied" and
 * {@code gate} contained no decryption at all, so the format the real producer emits was
 * one this repo could not consume. Only the key <em>storage</em> (Secret Manager) existed.
 *
 * <p>Decryption is deliberately all-or-nothing and side-effect free: it returns bytes or throws
 * {@link Gate.GateFailure}, and {@link Gate#run} writes nothing until every check —
 * decrypt, checksum, record count — has passed. A wrong key therefore leaves no rows and no files,
 * the same guarantee a corrupted {@code .CHS} already gave.
 */
public final class Pgp {

  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private Pgp() {}

  /** A private key file (ASCII-armoured or binary) and the passphrase protecting it. */
  public record Key(Path privateKeyFile, char[] passphrase) {

    /**
     * From {@code PGP_PRIVATE_KEY_FILE} / {@code PGP_PASSPHRASE}, or {@code null} when no key is
     * configured — which is a perfectly normal state for a plain (unencrypted) delivery.
     */
    public static Key fromEnvironment() {
      String file = System.getenv("PGP_PRIVATE_KEY_FILE");
      if (file == null || file.isBlank()) {
        return null;
      }
      String pass = System.getenv("PGP_PASSPHRASE");
      return new Key(Path.of(file), pass == null ? new char[0] : pass.toCharArray());
    }
  }

  /** Decrypts {@code ciphertext} with {@code key}, or throws {@link Gate.GateFailure}. */
  public static byte[] decrypt(byte[] ciphertext, Key key) {
    try (InputStream keyIn = PGPUtil.getDecoderStream(Files.newInputStream(key.privateKeyFile()))) {
      PGPSecretKeyRingCollection secretKeys =
          new PGPSecretKeyRingCollection(keyIn, new JcaKeyFingerprintCalculator());

      JcaPGPObjectFactory factory =
          new JcaPGPObjectFactory(PGPUtil.getDecoderStream(new ByteArrayInputStream(ciphertext)));
      Object first = factory.nextObject();
      PGPEncryptedDataList encrypted =
          first instanceof PGPEncryptedDataList list ? list : (PGPEncryptedDataList) factory.nextObject();
      if (encrypted == null) {
        throw new Gate.GateFailure("extraction refused: the .pgp file holds no encrypted data");
      }

      PGPPrivateKey privateKey = null;
      PGPPublicKeyEncryptedData data = null;
      for (PGPEncryptedData candidate : encrypted) {
        if (candidate instanceof PGPPublicKeyEncryptedData pk) {
          PGPSecretKey secret = secretKeys.getSecretKey(pk.getKeyID());
          if (secret != null) {
            privateKey =
                secret.extractPrivateKey(
                    new JcePBESecretKeyDecryptorBuilder().setProvider("BC").build(key.passphrase()));
            data = pk;
            break;
          }
        }
      }
      if (data == null) {
        // The most likely operational failure — the producer encrypted to a different key — so it
        // gets its own message rather than a generic "decryption failed".
        throw new Gate.GateFailure(
            "extraction refused: the bundle is not encrypted to any key in " + key.privateKeyFile()
                + " (wrong key, or the producer used a different recipient)");
      }

      InputStream clear =
          data.getDataStream(new JcePublicKeyDataDecryptorFactoryBuilder().setProvider("BC").build(privateKey));
      JcaPGPObjectFactory plain = new JcaPGPObjectFactory(clear);
      Object message = plain.nextObject();
      if (message instanceof PGPCompressedData compressed) {
        plain = new JcaPGPObjectFactory(compressed.getDataStream());
        message = plain.nextObject();
      }
      if (!(message instanceof PGPLiteralData literal)) {
        throw new Gate.GateFailure("extraction refused: the decrypted PGP message holds no literal data");
      }
      byte[] out = literal.getInputStream().readAllBytes();

      // A tampered ciphertext can decrypt to garbage without throwing; the modification-detection
      // code is what says so. Checked AFTER reading, as the API requires.
      if (data.isIntegrityProtected() && !data.verify()) {
        throw new Gate.GateFailure("extraction refused: PGP integrity check failed — the bundle was modified");
      }
      return out;
    } catch (Gate.GateFailure e) {
      throw e;
    } catch (PGPException e) {
      // Wrong passphrase surfaces here as "checksum mismatch" / "exception decrypting secret key".
      throw new Gate.GateFailure("extraction refused: PGP decryption failed — " + e.getMessage());
    } catch (IOException | RuntimeException e) {
      throw new Gate.GateFailure(
          "extraction refused: could not read the PGP bundle or key — " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
