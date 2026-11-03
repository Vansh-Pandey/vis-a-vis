package jp.vinsight.api.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvTest {
  @Test
  void parsesQuotesCommasAndNewlinesInsideCells() {
    var rows = Csv.parse("a,b\r\n\"x, y\",\"line1\nline2\"\n\"he said \"\"hi\"\"\",\n", 10);
    assertEquals(List.of(List.of("a", "b"), List.of("x, y", "line1\nline2"), List.of("he said \"hi\"", "")), rows);
  }

  @Test
  void refusesAnUnclosedQuoteAndTooManyRows() {
    assertThrows(IllegalArgumentException.class, () -> Csv.parse("a,\"b\n", 10));
    assertThrows(IllegalArgumentException.class, () -> Csv.parse("1\n2\n3\n4\n", 2));
  }

  @Test
  void readsUtf8WithBomAndShiftJis() {
    byte[] bom = "﻿山田,花子".getBytes(StandardCharsets.UTF_8);
    assertEquals("山田,花子", Csv.decode(bom));
    byte[] sjis = "山田,花子".getBytes(Charset.forName("windows-31j"));
    assertEquals("山田,花子", Csv.decode(sjis));
  }

  @Test
  void neutralisesSpreadsheetFormulas() {
    assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", Csv.cell("=HYPERLINK(\"x\")"));
    assertEquals("\"'+1\"", Csv.cell("+1"));
    assertEquals("\"'@SUM(A1)\"", Csv.cell("@SUM(A1)"));
    assertEquals("\"plain\"", Csv.cell("plain"));
  }
}
