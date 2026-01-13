/*
 * SPDX-License-Identifier: Apache-2.0
 * Stub to satisfy KNN codec class loading - contains all public method signatures from OpenSearch Setting.java
 */
package org.opensearch.common.settings;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.Iterator;
import java.util.Collections;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.common.unit.TimeValue;

public class Setting<T> {
    protected final Function<Settings, String> defaultValue = s -> "";
    protected final Setting<T> fallbackSetting = null;

    public enum Property {
        Dynamic, NodeScope, IndexScope, Deprecated, Final, Filtered, InternalIndex, PrivateIndex,
        OperatorDynamic, Consistent, NotCopyableOnResize, ExtensionScope, UnmodifiableOnRestore
    }

    @FunctionalInterface
    public interface Validator<T> {
        void validate(T value);
        default void validate(T value, Map<Setting<?>, Object> settings) { validate(value); }
        default void validate(T value, Map<Setting<?>, Object> settings, boolean isPresent) { validate(value, settings); }
        default Iterator<Setting<?>> settings() { return Collections.emptyIterator(); }
    }

    public interface SettingDependency {
        Setting<?> getSetting();
        default void validate(String key, Object value, Object dependency) {}
    }

    public interface AffixSettingDependency extends SettingDependency {
        @Override AffixSetting<?> getSetting();
    }

    public interface Key { boolean match(String key); }

    public static class SimpleKey implements Key {
        protected final String key;
        public SimpleKey(String key) { this.key = key; }
        @Override public boolean match(String key) { return this.key.equals(key); }
        @Override public String toString() { return key; }
    }

    // Constructors
    public Setting(Key key, Function<Settings, String> defaultValue, Function<String, T> parser, Property... properties) {}
    public Setting(Key key, Function<Settings, String> defaultValue, Function<String, T> parser, Validator<T> validator, Property... properties) {}
    public Setting(String key, String defaultValue, Function<String, T> parser, Property... properties) {}
    public Setting(String key, String defaultValue, Function<String, T> parser, Validator<T> validator, Property... properties) {}
    public Setting(String key, Function<Settings, String> defaultValue, Function<String, T> parser, Property... properties) {}
    public Setting(Key key, Setting<T> fallbackSetting, Function<String, T> parser, Property... properties) {}
    public Setting(String key, Setting<T> fallbackSetting, Function<String, T> parser, Property... properties) {}
    public Setting(Key key, Setting<T> fallbackSetting, Function<Settings, String> defaultValue, Function<String, T> parser, Validator<T> validator, Property... properties) {}

    // Instance methods
    public String getKey() { return ""; }
    public Key getRawKey() { return new SimpleKey(""); }
    public boolean isDynamic() { return false; }
    public boolean isFinal() { return false; }
    public boolean isFiltered() { return false; }
    public boolean hasNodeScope() { return false; }
    public boolean hasIndexScope() { return false; }
    public boolean isDeprecated() { return false; }
    public T get(Settings settings) { return null; }
    public T get(Settings primary, Settings secondary) { return null; }
    public T getDefault(Settings settings) { return null; }
    public String getDefaultRaw(Settings settings) { return ""; }
    public boolean exists(Settings settings) { return false; }
    public boolean existsOrFallbackExists(Settings settings) { return false; }
    public Setting<T> getConcreteSetting(String key) { return this; }
    public Set<SettingDependency> getSettingsDependencies(String key) { return Collections.emptySet(); }
    String getRaw(Settings settings) { return ""; }
    String innerGetRaw(Settings settings) { return ""; }
    boolean hasComplexMatcher() { return false; }
    public void diff(Settings.Builder builder, Settings source, Settings defaultSettings) {}

    // Version
    public static Setting<Object> versionSetting(String key, Object defaultValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }

    // Float settings
    public static Setting<Float> floatSetting(String key, float defaultValue, Property... properties) { return new Setting<>(key, "", s -> 0f, properties); }
    public static Setting<Float> floatSetting(String key, float defaultValue, float minValue, Property... properties) { return new Setting<>(key, "", s -> 0f, properties); }
    public static Setting<Float> floatSetting(String key, float defaultValue, float minValue, float maxValue, Property... properties) { return new Setting<>(key, "", s -> 0f, properties); }
    public static Setting<Float> floatSetting(String key, float defaultValue, float minValue, float maxValue, Validator<Float> validator, Property... properties) { return new Setting<>(key, "", s -> 0f, properties); }
    public static Setting<Float> floatSetting(String key, Setting<Float> fallbackSetting, Property... properties) { return new Setting<>(key, "", s -> 0f, properties); }
    public static Setting<Float> floatSetting(String key, Setting<Float> fallbackSetting, float minValue, Property... properties) { return new Setting<>(key, "", s -> 0f, properties); }
    public static Setting<Float> floatSetting(String key, Setting<Float> fallbackSetting, float minValue, float maxValue, Property... properties) { return new Setting<>(key, "", s -> 0f, properties); }
    public static Setting<Float> floatSetting(String key, Setting<Float> fallbackSetting, float minValue, float maxValue, Validator<Float> validator, Property... properties) { return new Setting<>(key, "", s -> 0f, properties); }

    // Integer settings
    public static Setting<Integer> intSetting(String key, int defaultValue, Property... properties) { return new Setting<>(key, "", s -> 0, properties); }
    public static Setting<Integer> intSetting(String key, int defaultValue, int minValue, Property... properties) { return new Setting<>(key, "", s -> 0, properties); }
    public static Setting<Integer> intSetting(String key, int defaultValue, int minValue, int maxValue, Property... properties) { return new Setting<>(key, "", s -> 0, properties); }
    public static Setting<Integer> intSetting(String key, int defaultValue, int minValue, Validator<Integer> validator, Property... properties) { return new Setting<>(key, "", s -> 0, properties); }
    public static Setting<Integer> intSetting(String key, int defaultValue, int minValue, int maxValue, Validator<Integer> validator, Property... properties) { return new Setting<>(key, "", s -> 0, properties); }
    public static Setting<Integer> intSetting(String key, Setting<Integer> fallbackSetting, Property... properties) { return new Setting<>(key, "", s -> 0, properties); }
    public static Setting<Integer> intSetting(String key, Setting<Integer> fallbackSetting, int minValue, Property... properties) { return new Setting<>(key, "", s -> 0, properties); }
    public static Setting<Integer> intSetting(String key, Setting<Integer> fallbackSetting, int minValue, int maxValue, Property... properties) { return new Setting<>(key, "", s -> 0, properties); }
    public static Setting<Integer> intSetting(String key, Setting<Integer> fallbackSetting, int minValue, Validator<Integer> validator, Property... properties) { return new Setting<>(key, "", s -> 0, properties); }
    public static Setting<Integer> intSetting(String key, Setting<Integer> fallbackSetting, int minValue, int maxValue, Validator<Integer> validator, Property... properties) { return new Setting<>(key, "", s -> 0, properties); }

    // Long settings
    public static Setting<Long> longSetting(String key, long defaultValue, Property... properties) { return new Setting<>(key, "", s -> 0L, properties); }
    public static Setting<Long> longSetting(String key, long defaultValue, long minValue, Property... properties) { return new Setting<>(key, "", s -> 0L, properties); }
    public static Setting<Long> longSetting(String key, long defaultValue, long minValue, long maxValue, Property... properties) { return new Setting<>(key, "", s -> 0L, properties); }
    public static Setting<Long> longSetting(String key, long defaultValue, long minValue, long maxValue, Validator<Long> validator, Property... properties) { return new Setting<>(key, "", s -> 0L, properties); }
    public static Setting<Long> longSetting(String key, Setting<Long> fallbackSetting, Property... properties) { return new Setting<>(key, "", s -> 0L, properties); }
    public static Setting<Long> longSetting(String key, Setting<Long> fallbackSetting, long minValue, Property... properties) { return new Setting<>(key, "", s -> 0L, properties); }
    public static Setting<Long> longSetting(String key, Setting<Long> fallbackSetting, long minValue, long maxValue, Property... properties) { return new Setting<>(key, "", s -> 0L, properties); }
    public static Setting<Long> longSetting(String key, Setting<Long> fallbackSetting, long minValue, Validator<Long> validator, Property... properties) { return new Setting<>(key, "", s -> 0L, properties); }
    public static Setting<Long> longSetting(String key, Setting<Long> fallbackSetting, long minValue, long maxValue, Validator<Long> validator, Property... properties) { return new Setting<>(key, "", s -> 0L, properties); }

    // Double settings
    public static Setting<Double> doubleSetting(String key, double defaultValue, Property... properties) { return new Setting<>(key, "", s -> 0d, properties); }
    public static Setting<Double> doubleSetting(String key, double defaultValue, double minValue, Property... properties) { return new Setting<>(key, "", s -> 0d, properties); }
    public static Setting<Double> doubleSetting(String key, double defaultValue, double minValue, double maxValue, Property... properties) { return new Setting<>(key, "", s -> 0d, properties); }
    public static Setting<Double> doubleSetting(String key, double defaultValue, double minValue, double maxValue, Validator<Double> validator, Property... properties) { return new Setting<>(key, "", s -> 0d, properties); }
    public static Setting<Double> doubleSetting(String key, double defaultValue, Validator<Double> validator, Property... properties) { return new Setting<>(key, "", s -> 0d, properties); }
    public static Setting<Double> doubleSetting(String key, Setting<Double> fallbackSetting, Property... properties) { return new Setting<>(key, "", s -> 0d, properties); }
    public static Setting<Double> doubleSetting(String key, Setting<Double> fallbackSetting, double minValue, Property... properties) { return new Setting<>(key, "", s -> 0d, properties); }
    public static Setting<Double> doubleSetting(String key, Setting<Double> fallbackSetting, double minValue, double maxValue, Property... properties) { return new Setting<>(key, "", s -> 0d, properties); }
    public static Setting<Double> doubleSetting(String key, Setting<Double> fallbackSetting, double minValue, double maxValue, Validator<Double> validator, Property... properties) { return new Setting<>(key, "", s -> 0d, properties); }
    public static Setting<Double> doubleSetting(String key, Setting<Double> fallbackSetting, Validator<Double> validator, Property... properties) { return new Setting<>(key, "", s -> 0d, properties); }

    // String settings
    public static Setting<String> simpleString(String key, Property... properties) { return new Setting<>(key, "", s -> "", properties); }
    public static Setting<String> simpleString(String key, Validator<String> validator, Property... properties) { return new Setting<>(key, "", s -> "", properties); }
    public static Setting<String> simpleString(String key, Validator<String> validator, Setting<String> fallback, Property... properties) { return new Setting<>(key, "", s -> "", properties); }
    public static Setting<String> simpleString(String key, String defaultValue, Validator<String> validator, Property... properties) { return new Setting<>(key, "", s -> "", properties); }
    public static Setting<String> simpleString(String key, Setting<String> fallback, Property... properties) { return new Setting<>(key, "", s -> "", properties); }
    public static Setting<String> simpleString(String key, Setting<String> fallback, Function<String, String> parser, Property... properties) { return new Setting<>(key, "", s -> "", properties); }
    public static Setting<String> simpleString(String key, String defaultValue, Property... properties) { return new Setting<>(key, "", s -> "", properties); }

    // Boolean settings
    public static Setting<Boolean> boolSetting(String key, boolean defaultValue, Property... properties) { return new Setting<>(key, "", s -> false, properties); }
    public static Setting<Boolean> boolSetting(String key, Setting<Boolean> fallbackSetting, Property... properties) { return new Setting<>(key, "", s -> false, properties); }
    public static Setting<Boolean> boolSetting(String key, Setting<Boolean> fallbackSetting, Validator<Boolean> validator, Property... properties) { return new Setting<>(key, "", s -> false, properties); }
    public static Setting<Boolean> boolSetting(String key, boolean defaultValue, Validator<Boolean> validator, Property... properties) { return new Setting<>(key, "", s -> false, properties); }
    public static Setting<Boolean> boolSetting(String key, Function<Settings, String> defaultValueFn, Property... properties) { return new Setting<>(key, "", s -> false, properties); }

    // ByteSize settings
    public static Setting<ByteSizeValue> byteSizeSetting(String key, ByteSizeValue value, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<ByteSizeValue> byteSizeSetting(String key, Setting<ByteSizeValue> fallbackSetting, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<ByteSizeValue> byteSizeSetting(String key, Function<Settings, String> defaultValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<ByteSizeValue> byteSizeSetting(String key, ByteSizeValue defaultValue, ByteSizeValue minValue, ByteSizeValue maxValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<ByteSizeValue> byteSizeSetting(String key, Function<Settings, String> defaultValue, ByteSizeValue minValue, ByteSizeValue maxValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }

    // MemorySize settings
    public static Setting<ByteSizeValue> memorySizeSetting(String key, ByteSizeValue defaultValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<ByteSizeValue> memorySizeSetting(String key, Function<Settings, String> defaultValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<ByteSizeValue> memorySizeSetting(String key, String defaultPercentage, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<ByteSizeValue> memorySizeSetting(String key, Setting<ByteSizeValue> fallbackSetting, Property... properties) { return new Setting<>(key, "", s -> null, properties); }

    // List settings
    public static <T> Setting<List<T>> listSetting(String key, List<String> defaultStringValue, Function<String, T> singleValueParser, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static <T> Setting<List<T>> listSetting(String key, List<String> defaultStringValue, Function<String, T> singleValueParser, Validator<List<T>> validator, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static <T> Setting<List<T>> listSetting(String key, Setting<List<T>> fallbackSetting, Function<String, T> singleValueParser, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static <T> Setting<List<T>> listSetting(String key, Function<String, T> singleValueParser, Function<Settings, List<String>> defaultStringValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static <T> Setting<List<T>> listSetting(String key, Function<String, T> singleValueParser, Function<Settings, List<String>> defaultStringValue, Validator<List<T>> validator, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static <T> Setting<List<T>> listSetting(String key, Setting<List<T>> fallbackSetting, Function<String, T> singleValueParser, Function<Settings, List<String>> defaultStringValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static <T> Setting<List<T>> listSetting(String key, Setting<List<T>> fallbackSetting, Function<String, T> singleValueParser, Function<Settings, List<String>> defaultStringValue, Validator<List<T>> validator, Property... properties) { return new Setting<>(key, "", s -> null, properties); }

    // Group settings
    public static Setting<Settings> groupSetting(String key, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<Settings> groupSetting(String key, Consumer<Settings> validator, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<Settings> groupSetting(String key, Setting<Settings> fallback, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<Settings> groupSetting(String key, Setting<Settings> fallback, Consumer<Settings> validator, Property... properties) { return new Setting<>(key, "", s -> null, properties); }

    // Time settings
    public static Setting<TimeValue> timeSetting(String key, TimeValue defaultValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<TimeValue> timeSetting(String key, Setting<TimeValue> fallbackSetting, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<TimeValue> timeSetting(String key, TimeValue defaultValue, TimeValue minValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<TimeValue> timeSetting(String key, Setting<TimeValue> fallbackSetting, TimeValue minValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<TimeValue> timeSetting(String key, Function<Settings, TimeValue> defaultValue, TimeValue minValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<TimeValue> timeSetting(String key, TimeValue defaultValue, TimeValue minValue, TimeValue maxValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<TimeValue> timeSetting(String key, TimeValue defaultValue, TimeValue minValue, Validator<TimeValue> validator, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<TimeValue> timeSetting(String key, Setting<TimeValue> fallBackSetting, Validator<TimeValue> validator, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<TimeValue> positiveTimeSetting(String key, TimeValue defaultValue, Property... properties) { return new Setting<>(key, "", s -> null, properties); }
    public static Setting<TimeValue> positiveTimeSetting(String key, Setting<TimeValue> fallbackSetting, Property... properties) { return new Setting<>(key, "", s -> null, properties); }

    // Affix settings
    public static <T> AffixSetting<T> prefixKeySetting(String prefix, Function<String, Setting<T>> delegateFactory) { return new AffixSetting<>(); }
    public static <T> AffixSetting<T> suffixKeySetting(String suffix, Function<String, Setting<T>> delegateFactory) { return new AffixSetting<>(); }
    public static <T> AffixSetting<T> affixKeySetting(String prefix, String suffix, Function<String, Setting<T>> delegateFactory, AffixSettingDependency... dependencies) { return new AffixSetting<>(); }
    public static <T> AffixSetting<T> affixKeySetting(String prefix, String suffix, BiFunction<String, String, Setting<T>> delegateFactory, AffixSettingDependency... dependencies) { return new AffixSetting<>(); }

    // AffixSetting inner class
    public static class AffixSetting<T> extends Setting<T> {
        public AffixSetting() { super("", "", s -> null); }
        public Set<AffixSettingDependency> getDependencies() { return Collections.emptySet(); }
        public Setting<T> getConcreteSettingForNamespace(String namespace) { return this; }
        public String getNamespace(Setting<T> concreteSetting) { return ""; }
        public java.util.stream.Stream<Setting<T>> getAllConcreteSettings(Settings settings) { return java.util.stream.Stream.empty(); }
        public Set<String> getNamespaces(Settings settings) { return Collections.emptySet(); }
        public Map<String, T> getAsMap(Settings settings) { return Collections.emptyMap(); }
    }
}
