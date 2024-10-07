package org.opensearch.migrations.data;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.experimental.UtilityClass;

import static org.opensearch.migrations.data.GeneratedData.createField;

@UtilityClass
public class Nested {

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
        "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
        "hello", "world", "data", "code", "example", "test", "random",
        "generate", "method", "class", "object", "function"
    };
    private static final int ONE_DAY_IN_MILLIS = 24 * 60 * 60 * 1000;

    public static ObjectNode generateNestedIndex() {
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

        var mappings = mapper.createObjectNode();
        mappings.put("dynamic", "strict");
        mappings.set("properties", properties);

        var index = mapper.createObjectNode();
        index.set("mappings", mappings);
        return index;
    }

    public static Stream<ObjectNode> generateNestedDocs(int numDocs) {
        var random = new Random(1L);
        var currentTime = System.currentTimeMillis();

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                var doc = mapper.createObjectNode();
                doc.put("qid", i + 1000);
                doc.put("user", randomUser(random));
                var creationTime = randomTimeWithin24Hours(currentTime, random);
                doc.put("creationDate", creationTime /** TODO */);
                doc.put("title", randomTitle(random));
                doc.put("tag", randomTag(random));
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
            var answerTime = randomTimeWithin24Hours(timeFrom, random);
            answer.put("date", answerTime /** TODO */);
            answer.set("user", randomUser(random));

            answers.add(answer);
        }
        return answers;
    }

    private static long randomTimeWithin24Hours(long timeFrom, Random random) {
        return timeFrom - random.nextInt(ONE_DAY_IN_MILLIS);
    }

    private static String randomUser(Random random) {
        return USER_NAMES[random.nextInt(USER_NAMES.length)] + " (" + random.nextInt(10) + 1000 + ")";
    }

    private static String randomTags(Random random) {
        var tags = mapper.createArrayNode();
        return TAGS[random.nextInt(TAGS.length)] + "v" + random.nextInt(10);
    }
}
