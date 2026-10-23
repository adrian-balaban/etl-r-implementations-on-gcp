package mig.gate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * The un-tar half of the bundle. Ported from
 * {@code apps/common/src/main/java/ro/mig/common/Archives.java}. Only reading is needed here —
 * writing lives in harness/bundle.py for this repo's local landing.
 */
public final class Archives {

  private Archives() {}

  public static Map<String, byte[]> unTarGz(byte[] bundle) {
    Map<String, byte[]> files = new LinkedHashMap<>();
    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bundle));
        TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        tar.transferTo(content);
        files.put(entry.getName(), content.toByteArray());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not read tar.gz bundle", e);
    }
    return files;
  }
}
