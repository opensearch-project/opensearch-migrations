package org.opensearch.migrations.data.workloads;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.data.IFieldCreator;
import org.opensearch.migrations.data.IRandomDataBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Workload based off of nested
 * https://github.com/opensearch-project/opensearch-benchmark-workloads/tree/main/nested
 */
@Slf4j
public class Nested implements Workload, IFieldCreator, IRandomDataBuilders {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] USER_NAMES = {
        "alice", "bob", "charlie", "david", "eve",
        "frank", "grace", "heidi", "ivan", "judy"
    };
    private static final String[] TAGS = {
        "java", "python", "c++", "javascript", "html",
        "css", "sql", "bash", "docker", "kubernetes"
    };
    private static final String[] WORDS = {
        "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog"
    };

    @Override
    public List<String> indexNames() {
        return List.of("sonested");
    }

    /**
     * Mirroring index configuration from 
     * https://github.com/opensearch-project/opensearch-benchmark-workloads/blob/main/nested/index.json
     */
    @Override
    public ObjectNode createIndex(ObjectNode defaultSettings) {
        return mapper.createObjectNode()
            .<ObjectNode>set("mappings", mapper.createObjectNode()
                .<ObjectNode>put("dynamic", "strict")
                .<ObjectNode>set("properties", mapper.createObjectNode()
                    .<ObjectNode>set("user", fieldKeyword())
                    .<ObjectNode>set("creationDate", fieldText())
                    .<ObjectNode>set("title", fieldText())
                    .<ObjectNode>set("qid", fieldKeyword())
                    .<ObjectNode>set("tag", fieldKeyword())
                    .<ObjectNode>set("answer_count", fieldInt())
                    .<ObjectNode>set("answers",  fieldNested()
                        .set("properties", mapper.createObjectNode()
                            .<ObjectNode>set("user", fieldKeyword())
                            .<ObjectNode>set("date", fieldDate())))))
            .<ObjectNode>set("settings", defaultSettings);
    }

    /**
     * Example generated document:
     {
         "title": "",
         "qid": "1405",
         "answers": [
             {
                 "date": 1728487507935,
                 "user": "frank (1006)"
             }
         ],
         "tag": [
             "bashv6"
         ],
         "user": "judy (1001)",
         "creationDate": 1728506897762
     }
     */
    @Override
    public Stream<ObjectNode> createDocs(int numDocs) {
        var currentTime = System.currentTimeMillis();

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                var random = new Random(i);
                var creationTime = randomTime(currentTime, random);
                return mapper.createObjectNode()
                    .<ObjectNode>put("title", randomTitle(random))
                    .<ObjectNode>put("qid", (i + 1000) + "")
                    .<ObjectNode>set("answers", randomAnswers(mapper, creationTime, random))
                    .<ObjectNode>set("tag", randomTags(random))
                    .<ObjectNode>put("user", randomUser(random))
                    .<ObjectNode>put("creationDate", creationTime);
            }
        );
    }

    private ArrayNode randomAnswers(ObjectMapper mapper, long timeFrom, Random random) {
        var answers = mapper.createArrayNode();
        var numAnswers = random.nextInt(5) + 1;

        for (int i = 0; i < numAnswers; i++) {
            long randomTime = randomTime(timeFrom, random);
            var answer = mapper.createObjectNode()
                .put("date", randomTime)
                .put("user", randomUser(random));
            answers.add(answer);
        }
        return answers;
    }

    private String randomUser(Random random) {
        // Extra random int simulates more users
        return randomElement(USER_NAMES, random) + " (" + (random.nextInt(10) + 1000) + ")";
    }

    private ArrayNode randomTags(Random random) {
        var tags = mapper.createArrayNode();
        var tagsToCreate = random.nextInt(3) + 1;

        for (int i = 0; i < tagsToCreate; i++) {
            tags.add(randomElement(TAGS, random) + "v" + random.nextInt(10)); // Extra random int simulates more tags
        }
        return tags;
    }

    private String randomTitle(Random random) {
        var titleWordLength = random.nextInt(5);
        var words = new ArrayList<String>();

        for (int i = 0; i < titleWordLength; i++) {
            words.add(randomElement(WORDS, random) + "" + random.nextInt(10)); // Extra random int simulates more words
        }
        return String.join(" ", words);
    }
}
