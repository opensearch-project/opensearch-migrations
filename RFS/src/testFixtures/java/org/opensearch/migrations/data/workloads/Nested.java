package org.opensearch.migrations.data.workloads;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.opensearch.migrations.data.FieldBuilders.createField;
import static org.opensearch.migrations.data.RandomDataBuilders.randomElement;
import static org.opensearch.migrations.data.RandomDataBuilders.randomTime;
import static org.opensearch.migrations.data.RandomDataBuilders.randomTimeISOString;

public class Nested implements Workload {

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

    @Override
    public ObjectNode createIndex(ObjectNode defaultSettings) {
        var properties = mapper.createObjectNode();
        properties.set("user", createField("keyword"));
        properties.set("creationDate", createField("date"));
        properties.set("title", createField("text"));
        properties.set("qid", createField("keyword"));
        properties.set("tag", createField("keyword"));
        properties.set("answer_count", createField("integer"));
        var answers = createField("nested");
        var answersProps = mapper.createObjectNode();
        answers.set("properties", answersProps);
        answersProps.set("user", createField("keyword"));
        answersProps.set("date", createField("date"));
        properties.set("answers", answers);

        var mappings = mapper.createObjectNode();
        mappings.put("dynamic", "strict");
        mappings.set("properties", properties);

        var index = mapper.createObjectNode();
        index.set("mappings", mappings);
        index.set("settings", defaultSettings);

        return index;
    }

    @Override
    public Stream<ObjectNode> createDocs(int numDocs) {
        var random = new Random(1L);
        var currentTime = System.currentTimeMillis();

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                var doc = mapper.createObjectNode();
                doc.put("qid", (i + 1000) + "");
                doc.put("user", randomUser(random));
                var creationTime = randomTime(currentTime, random);
                doc.put("creationDate", creationTime);
                doc.put("title", randomTitle(random));
                doc.set("tag", randomTags(random));
                var children = generateAnswers(mapper, creationTime, random);
                doc.set("answers", children);
                return doc;
            }
        );
    }

    private static ArrayNode generateAnswers(ObjectMapper mapper, long timeFrom, Random random) {
        var answers = mapper.createArrayNode();
        var numAnswers = random.nextInt(5) + 1; // 1 to 5

        for (int i = 0; i < numAnswers; i++) {
            var answer = mapper.createObjectNode();
            answer.put("date", randomTime(timeFrom, random));
            answer.put("user", randomUser(random));

            answers.add(answer);
        }
        return answers;
    }

    private static String randomUser(Random random) {
        // Extra random int simulates more users
        return randomElement(USER_NAMES, random) + " (" + (random.nextInt(10) + 1000) + ")";
    }

    private static ArrayNode randomTags(Random random) {
        var tags = mapper.createArrayNode();
        var tagsToCreate = random.nextInt(3);

        for (int i = 0; i < tagsToCreate; i++) {
            tags.add(randomElement(TAGS, random) + "v" + random.nextInt(10)); // Extra random int simulates more tags
        }
        return tags;
    }

    private static String randomTitle(Random random) {
        var titleWordLength = random.nextInt(5);
        var words = new ArrayList<String>();

        for (int i = 0; i < titleWordLength; i++) {
            words.add(randomElement(WORDS, random) + "" + random.nextInt(10)); // Extra random int simulates more words
        }
        return words.stream().collect(Collectors.joining(" "));
    }
}
