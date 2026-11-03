package jp.vinsight.api.importer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** RFC 4180 CSV reading and safe writing. Reads UTF-8 (with or without BOM) or Shift_JIS (Excel's default in Japan). */
public final class Csv {
  private Csv() {}

  public static String decode(byte[] bytes) {
    int off = bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF ? 3 : 0;
    try {
      return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
    } catch (CharacterCodingException notUtf8) {
      return new String(bytes, Charset.forName("windows-31j"));
    }
  }

  /** Parses the text into rows; throws IllegalArgumentException for an unclosed quote. */
  public static List<List<String>> parse(String text, int maxRows) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false, any = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; } else quoted = false;
        } else cell.append(c);
        continue;
      }
      switch (c) {
        case '"' -> { quoted = true; any = true; }
        case ',' -> { row.add(cell.toString()); cell.setLength(0); any = true; }
        case '\r' -> {}
        case '\n' -> {
          row.add(cell.toString()); cell.setLength(0);
          if (!(row.size() == 1 && row.get(0).isEmpty() && !any)) rows.add(row);
          if (rows.size() > maxRows) throw new IllegalArgumentException("行数が多すぎます（" + maxRows + "行まで）。");
          row = new ArrayList<>(); any = false;
        }
        default -> { cell.append(c); any = true; }
      }
    }
    if (quoted) throw new IllegalArgumentException("ダブルクォートが閉じられていない行があります。");
    if (any || cell.length() > 0) { row.add(cell.toString()); rows.add(row); }
    return rows;
  }

  /** One CSV cell. Text starting with = + - @ (or a tab/CR) gets a leading ' so Excel shows it instead of running it. */
  public static String cell(Object v) {
    String s = v == null ? "" : v.toString();
    if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }
}
