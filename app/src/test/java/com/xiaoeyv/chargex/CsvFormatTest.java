package com.xiaoeyv.chargex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CsvFormatTest {

    @Test
    public void buildsWellFormedRows() {
        SampleRing ring = new SampleRing();
        // 模拟充电 3 秒：4.2V → 4.21V，2A，35°C
        for (int i = 0; i < 3; i++) {
            ring.add(1_700_000_000_000L + i * 1000L, 4.20 + i * 0.01, 2.0, 35.0, true, 70 + i);
        }
        String csv = CsvFormat.build(ring.snapshot());

        assertTrue("必须带 UTF-8 BOM", csv.charAt(0) == '\uFEFF');

        String[] lines = csv.substring(1).split("\n");
        assertEquals(4, lines.length); // 表头 + 3 行数据
        assertEquals(CsvFormat.header().trim(), lines[0]);

        String[] cols = lines[1].split(",");
        assertEquals(8, cols.length);
        assertEquals("1700000000000", cols[1]);
        assertEquals("0.0", cols[2]);
        assertEquals("4.200", cols[3]);
        assertEquals("2.000", cols[4]);
        assertEquals("35.0", cols[5]);
        assertEquals("8.40", cols[6]);
        assertEquals("charging", cols[7]);

        String[] cols3 = lines[3].split(",");
        assertEquals("2.0", cols3[2]);
        assertEquals("4.220", cols3[3]);
    }

    @Test
    public void handlesEmptyAndNaN() {
        String empty = CsvFormat.build(new SampleRing().snapshot());
        String[] emptyLines = empty.substring(1).split("\n");
        assertEquals("只有表头", 1, emptyLines.length);

        SampleRing ring = new SampleRing();
        ring.add(1L, Double.NaN, Double.NaN, Double.NaN, false, -1);
        String csv = CsvFormat.build(ring.snapshot());
        String row = csv.substring(1).split("\n")[1];
        String[] cols = row.split(",", -1);
        assertEquals("", cols[3]);
        assertEquals("", cols[4]);
        assertEquals("", cols[5]);
        assertEquals("discharging", cols[7]);
    }

    @Test
    public void fileNameIsTimestamped() {
        String n = CsvFormat.newFileName();
        assertTrue(n.startsWith("charge_"));
        assertTrue(n.endsWith(".csv"));
        assertEquals(26, n.length()); // charge_(7) + yyyyMMdd_HHmmss(15) + .csv(4)
    }
}
