package org.opensearch.migrations.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Format;
import org.opensearch.migrations.cli.Items;
import org.opensearch.migrations.cli.Transformers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** All shared cli result information */
public interface MigrationItemResult extends Result {
    Clusters getClusters();
    Items getItems();
    Transformers getTransformations();

    default List<String> collectErrors() {
        var errors = new ArrayList<String>();
        if (getClusters() == null || getClusters().getSource() == null) {
            errors.add("No source was defined");
        }
        if (getClusters() == null || getClusters().getTarget() == null) {
            errors.add("No target was defined");
        }

        if (getItems() != null) {
            errors.addAll(getItems().getAllErrors());
        }
        return errors;
    }

    default String asCliOutput() {
        var sb = new StringBuilder();
        if (getClusters() != null) {
            sb.append(getClusters().asCliOutput() + System.lineSeparator());
        }
        if (getItems() != null) {
            sb.append(getItems().asCliOutput() + System.lineSeparator());
        }
        if (getTransformations() != null) {
            sb.append(getTransformations().asCliOutput()).append(System.lineSeparator());
        }
        sb.append("Results:" + System.lineSeparator());
        var innerErrors = collectErrors();
        if (Strings.isNotBlank(getErrorMessage()) || !innerErrors.isEmpty()) {
            sb.append(Format.indentToLevel(1) + "Issue(s) detected" + System.lineSeparator());
            sb.append("Issues:" + System.lineSeparator());
            if (Strings.isNotBlank(getErrorMessage())) {
                sb.append(Format.indentToLevel(1) + getErrorMessage() + System.lineSeparator());
            }
            if (!innerErrors.isEmpty()) {
                innerErrors.forEach(err -> sb.append(Format.indentToLevel(1) + err + System.lineSeparator()));
            }
        } else {
            sb.append(Format.indentToLevel(1) + getExitCode() + " issue(s) detected" + System.lineSeparator());
        }
        return sb.toString();
    }
    
    @Override
    default String asJsonOutput() {
        Map<String, Object> json = new HashMap<>();
        
        if (getClusters() != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                json.put("clusters", mapper.readTree(getClusters().asJsonOutput()));
            } catch (Exception e) {
                json.put("clusters", "Error parsing clusters JSON");
            }
        }
        
        if (getItems() != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                json.put("items", mapper.readTree(getItems().asJsonOutput()));
            } catch (Exception e) {
                json.put("items", "Error parsing items JSON");
            }
        }
        
        if (getTransformations() != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                json.put("transformations", mapper.readTree(getTransformations().asJsonOutput()));
            } catch (Exception e) {
                json.put("transformations", "Error parsing transformations JSON");
            }
        }
        
        List<String> errors = collectErrors();
        json.put("errors", errors);
        json.put("errorCount", getExitCode());
        
        if (Strings.isNotBlank(getErrorMessage())) {
            json.put("errorMessage", getErrorMessage());
        }
        
        try {
            return new ObjectMapper().writeValueAsString(json);
        } catch (JsonProcessingException e) {
            Logger logger = LoggerFactory.getLogger(MigrationItemResult.class);
            logger.error("Error converting result to JSON", e);
            return "{ \"error\": \"Failed to convert result to JSON\" }";
        }
    }
}
