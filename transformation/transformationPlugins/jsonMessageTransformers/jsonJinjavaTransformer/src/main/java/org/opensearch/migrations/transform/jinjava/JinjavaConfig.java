package org.opensearch.migrations.transform.jinjava;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JinjavaConfig {
   @JsonProperty("regexReplacementConversionPatterns")
   private List<Map.Entry<String, String>>  regexReplacementConversionPatterns;

   @JsonProperty("regexReplacementConversionPatterns")
   Map<String, String> namedScripts;
}
