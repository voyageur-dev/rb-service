package revisionbuddy;

import java.util.*;
import java.util.function.Function;

import revisionbuddy.models.GetMetadataResponse;
import revisionbuddy.models.GetQuestionResponse;
import revisionbuddy.models.Question;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Handler for requests to Lambda function.
 */
public class App implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final String GET_QUESTIONS_PATH = "GET /rb/questions";
    private static final String GET_EXAM_METADATA = "GET /rb/{examId}/metadata";

    private final String questionsTableName;
    private final DynamoDbClient client;
    private final Gson gson;

    public App() {
        this.questionsTableName = System.getenv("QUESTIONS_TABLE_NAME");
        this.client = DynamoDbClient.create();
        this.gson = new GsonBuilder().create();
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        String path = event.getRouteKey();

        return switch (path) {
            case GET_QUESTIONS_PATH -> getQuestions(event);
            case GET_EXAM_METADATA -> getExamMetadata(event);
            default -> APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(404)
                    .withBody("Path Not Found")
                    .build();
        };
    }

    private APIGatewayV2HTTPResponse getExamMetadata(APIGatewayV2HTTPEvent event) {
        try {
            Map<String, String> pathParams = event.getPathParameters();
            String examId = pathParams.getOrDefault("examId", "aws-dva-c02");

            List<Integer> questionIds = new ArrayList<>();

            Map<String, AttributeValue> exclusiveStartKey = null;

            do {
                QueryRequest.Builder queryBuilder = QueryRequest.builder()
                        .tableName(questionsTableName)
                        .keyConditionExpression("exam_id = :exam_id")
                        .expressionAttributeValues(Map.of(":exam_id", AttributeValue.builder().s(examId).build()))
                        .projectionExpression("question_id");

                if (exclusiveStartKey != null) {
                    queryBuilder.exclusiveStartKey(exclusiveStartKey);
                }

                QueryResponse response = client.query(queryBuilder.build());

                for (Map<String, AttributeValue> item : response.items()) {
                    int questionId = Integer.parseInt(item.get("question_id").n());
                    questionIds.add(questionId);
                }

                exclusiveStartKey = response.lastEvaluatedKey();

            } while (exclusiveStartKey != null && !exclusiveStartKey.isEmpty());


            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withBody(gson.toJson(new GetMetadataResponse(questionIds)))
                    .build();
        } catch (Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withBody("Error getting metadata: " + e.getMessage())
                    .build();
        }
    }

    private APIGatewayV2HTTPResponse getQuestions(APIGatewayV2HTTPEvent event) {
        try {
            Map<String, String> queryParams = event.getQueryStringParameters();
            String examId = queryParams.getOrDefault("examId", "aws-dva-c02");
            int lastEvaluatedKey = Integer.parseInt(queryParams.getOrDefault("lastEvaluatedKey", "0"));
            int pageSize = Integer.parseInt(queryParams.getOrDefault("pageSize", "10"));

            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":exam_id", AttributeValue.builder().s(examId).build());

            Map<String, AttributeValue> exclusiveStartKey = new HashMap<>();
            exclusiveStartKey.put("exam_id", AttributeValue.builder().s(examId).build());
            exclusiveStartKey.put("question_id", AttributeValue.builder().n(String.valueOf(lastEvaluatedKey)).build());

            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(questionsTableName)
                    .keyConditionExpression("exam_id = :exam_id")
                    .expressionAttributeValues(expressionAttributeValues)
                    .limit(pageSize)
                    .exclusiveStartKey(exclusiveStartKey)
                    .build();

            QueryResponse resp = client.query(queryRequest);

            List<Question> questions = resp.items().stream().map(mapToQuestion()).toList();

            GetQuestionResponse getQuestionResponse = new GetQuestionResponse(questions);

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withBody(gson.toJson(getQuestionResponse))
                    .build();
        } catch (Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withBody("Error getting questions: " + e.getMessage())
                    .build();
        }
    }

    private Function<Map<String, AttributeValue>, Question> mapToQuestion() {
        return item -> new Question(
                item.get("exam_id").s(),
                Integer.parseInt(item.get("question_id").n()),
                item.get("options").l().stream().map(AttributeValue::m).map(mapToOption()).toList(),
                item.get("question").s(),
                Optional.of(item.getOrDefault("s3_image_urls", AttributeValue.builder().build()).l()).orElse(Collections.emptyList()).stream().map(AttributeValue::s).toList(),
                item.get("source_url").s()
        );
    }

    private Function<Map<String, AttributeValue>, Question.Option> mapToOption() {
        return option -> new Question.Option(
                option.get("is_correct").bool(),
                option.get("text").s(),
                Optional.of(option.getOrDefault("s3_image_urls", AttributeValue.builder().build()).l()).orElse(Collections.emptyList()).stream().map(AttributeValue::s).toList()
        );
    }
}
