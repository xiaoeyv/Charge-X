package com.xiaoeyv.chargex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** DeviceInfo 的文案映射：内核给的英文串要翻成人话，缺失时不能露出 null */
public class DeviceInfoTest {

    @Test
    public void technologyIsTranslated() {
        DeviceInfo d = new DeviceInfo();
        assertEquals("未提供", d.technologyLabel());

        d.technology = "Li-ion";
        assertEquals("锂离子（Li-ion）", d.technologyLabel());

        d.technology = "Li-poly";
        assertEquals("锂聚合物", d.technologyLabel());

        d.technology = "LiFePO4";
        assertEquals("磷酸铁锂", d.technologyLabel());

        d.technology = "  Li-ion  ";
        assertEquals("锂离子（Li-ion）", d.technologyLabel());
    }

    @Test
    public void unknownTechnologyPassesThrough() {
        DeviceInfo d = new DeviceInfo();
        d.technology = "UnKnownTech";
        assertEquals("UnKnownTech", d.technologyLabel());
    }

    @Test
    public void kernelStatusIsTranslated() {
        assertEquals("正在充电", DeviceInfo.statusLabel("Charging"));
        assertEquals("正在放电", DeviceInfo.statusLabel("Discharging"));
        assertEquals("已充满", DeviceInfo.statusLabel("Full"));
        assertEquals("未充电", DeviceInfo.statusLabel("Not charging"));
        assertEquals("正在充电", DeviceInfo.statusLabel("Charging ")); // 尾部空格也要能识别
        assertNull(DeviceInfo.statusLabel(null));
    }

    @Test
    public void kernelHealthIsTranslated() {
        assertEquals("状态正常", DeviceInfo.healthLabel("Good"));
        assertEquals("电池过热", DeviceInfo.healthLabel("Overheat"));
        assertEquals("已损坏", DeviceInfo.healthLabel("Dead"));
        assertNull(DeviceInfo.healthLabel(null));
    }

    @Test
    public void unknownTextPassesThrough() {
        assertEquals("Warm", DeviceInfo.healthLabel("Warm"));
        assertEquals("Unknown", DeviceInfo.statusLabel("Unknown"));
    }
}
