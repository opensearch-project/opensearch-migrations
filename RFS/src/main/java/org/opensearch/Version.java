package org.opensearch;

public class Version implements Comparable<Version> {
    public static final int V_EMPTY_ID = 0;
    public static final Version V_EMPTY = new Version(V_EMPTY_ID);
    public static final Version V_2_0_0 = new Version(2000099);
    public static final Version V_2_0_1 = new Version(2000199);
    public static final Version V_2_1_0 = new Version(2010099);
    public static final Version V_2_2_0 = new Version(2020099);
    public static final Version V_2_2_1 = new Version(2020199);
    public static final Version V_2_3_0 = new Version(2030099);
    public static final Version V_2_4_0 = new Version(2040099);
    public static final Version V_2_4_1 = new Version(2040199);
    public static final Version V_2_4_2 = new Version(2040299);
    public static final Version V_2_5_0 = new Version(2050099);
    public static final Version V_2_5_1 = new Version(2050199);
    public static final Version V_2_6_0 = new Version(2060099);
    public static final Version V_2_6_1 = new Version(2060199);
    public static final Version V_2_7_0 = new Version(2070099);
    public static final Version V_2_7_1 = new Version(2070199);
    public static final Version V_2_8_0 = new Version(2080099);
    public static final Version V_2_8_1 = new Version(2080199);
    public static final Version V_2_9_0 = new Version(2090099);
    public static final Version V_2_9_1 = new Version(2090199);
    public static final Version V_2_10_0 = new Version(2100099);
    public static final Version V_2_10_1 = new Version(2100199);
    public static final Version V_2_11_0 = new Version(2110099);
    public static final Version V_2_11_1 = new Version(2110199);
    public static final Version V_2_11_2 = new Version(2110299);
    public static final Version V_2_12_0 = new Version(2120099);
    public static final Version V_2_12_1 = new Version(2120199);
    public static final Version V_2_13_0 = new Version(2130099);
    public static final Version V_2_13_1 = new Version(2130199);
    public static final Version V_2_14_0 = new Version(2140099);
    public static final Version V_2_14_1 = new Version(2140199);
    public static final Version V_2_15_0 = new Version(2150099);
    public static final Version V_2_15_1 = new Version(2150199);
    public static final Version V_2_16_0 = new Version(2160099);
    public static final Version V_2_16_1 = new Version(2160199);
    public static final Version V_2_17_0 = new Version(2170099);
    public static final Version V_2_17_1 = new Version(2170199);
    public static final Version V_2_17_2 = new Version(2170299);
    public static final Version V_2_18_0 = new Version(2180099);
    public static final Version V_2_18_1 = new Version(2180199);
    public static final Version V_2_19_0 = new Version(2190099);
    public static final Version V_2_19_1 = new Version(2190199);
    public static final Version V_2_19_2 = new Version(2190299);
    public static final Version V_2_19_3 = new Version(2190399);
    public static final Version V_2_19_4 = new Version(2190499);
    public static final Version V_2_19_5 = new Version(2190599);
    public static final Version V_3_0_0 = new Version(3000099);
    public static final Version V_3_1_0 = new Version(3010099);
    public static final Version V_3_2_0 = new Version(3020099);
    public static final Version V_3_3_0 = new Version(3030099);
    public static final Version V_3_3_1 = new Version(3030199);
    public static final Version V_3_3_2 = new Version(3030299);
    public static final Version V_3_4_0 = new Version(3040099);
    public static final Version V_3_5_0 = new Version(3050099);
    public static final Version CURRENT = V_3_5_0;

    public final int id;
    public final byte major;
    public final byte minor;
    public final byte revision;
    public final byte build;

    Version(int id) {
        this.id = id;
        this.major = (byte) ((id / 1000000) % 100);
        this.minor = (byte) ((id / 10000) % 100);
        this.revision = (byte) ((id / 100) % 100);
        this.build = (byte) (id % 100);
    }

    public static Version fromId(int id) { return new Version(id); }
    public static Version fromString(String version) { return CURRENT; }
    public static Version min(Version v1, Version v2) { return v1.id <= v2.id ? v1 : v2; }
    public static Version max(Version v1, Version v2) { return v1.id >= v2.id ? v1 : v2; }

    public boolean after(Version v) { return id > v.id; }
    public boolean onOrAfter(Version v) { return id >= v.id; }
    public boolean before(Version v) { return id < v.id; }
    public boolean onOrBefore(Version v) { return id <= v.id; }
    public int compareTo(Version o) { return Integer.compare(id, o.id); }
    public Version minimumCompatibilityVersion() { return V_EMPTY; }
    public Version minimumIndexCompatibilityVersion() { return V_EMPTY; }
    public boolean isCompatible(Version v) { return true; }
    public String toString() { return major + "." + minor + "." + revision; }
    public boolean equals(Object o) { return o instanceof Version && ((Version)o).id == id; }
    public int hashCode() { return id; }
}
