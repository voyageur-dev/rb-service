package revisionbuddy;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import com.google.gson.JsonObject;
import revisionbuddy.models.GetBookmarksResponse;
import revisionbuddy.models.GetMetadataResponse;
import revisionbuddy.models.GetQuestionsResponse;
import revisionbuddy.models.Question;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Handler for requests to Lambda function.
 */
public class App implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final String GET_QUESTIONS_PATH = "GET /rb/questions";
    private static final String GET_BOOKMARKS_PATH = "GET/rb/bookmarks";
    private static final String GET_EXAM_METADATA = "GET /rb/{examId}/metadata";

    private static final String POST_BOOKMARKS_PATH = "POST /rb/bookmarks";

    private final String questionsTableName;
    private final String bookmarkTableName;
    private final List<String> examIds;

    private final DynamoDbClient client;
    private final Gson gson;

    public App() {
        this.questionsTableName = System.getenv("QUESTIONS_TABLE_NAME");
        this.bookmarkTableName = System.getenv("BOOKMARK_TABLE_NAME");
        this.examIds = List.of(System.getenv("EXAM_IDS").split(","));
        this.client = DynamoDbClient.create();
        this.gson = new GsonBuilder().create();
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        String path = event.getRouteKey();

        return switch (path) {
            case GET_QUESTIONS_PATH -> getQuestions(event);
            case GET_BOOKMARKS_PATH -> getBookmarks(event);
            case GET_EXAM_METADATA -> getExamMetadata(event);
            case POST_BOOKMARKS_PATH -> createBookmarks(event);
            default -> APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(404)
                    .withBody("Path Not Found")
                    .build();
        };
    }

    private APIGatewayV2HTTPResponse getExamMetadata(APIGatewayV2HTTPEvent event) {
        try {
            Map<String, String> pathParams = event.getPathParameters();

            List<GetMetadataResponse.Metadata> metadata = new ArrayList<>();

            for (String examId : examIds) {
                QueryRequest queryRequest = QueryRequest.builder()
                        .tableName(questionsTableName)
                        .keyConditionExpression("exam_id" + " = :examId")
                        .expressionAttributeValues(Map.of(":examId", AttributeValue.fromS(examId)))
                        .select(Select.COUNT)
                        .build();

                QueryResponse response = client.query(queryRequest);
                metadata.add(new GetMetadataResponse.Metadata(examId, response.count()));
            }

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withBody(gson.toJson(new GetMetadataResponse(metadata)))
                    .build();
        } catch (Exception e) {
            System.out.println(e.getMessage());

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withBody("Error getting metadata")
                    .build();
        }
    }

    private APIGatewayV2HTTPResponse getQuestions(APIGatewayV2HTTPEvent event) {
        try {
            Map<String, String> queryParams = event.getQueryStringParameters();
            String examId = queryParams.getOrDefault("examId", "aws-dva-c02");
            int lastEvaluatedKey = Integer.parseInt(queryParams.getOrDefault("lastEvaluatedKey", "-1"));
            int pageSize = Integer.parseInt(queryParams.getOrDefault("pageSize", "10"));

            QueryRequest.Builder builder = QueryRequest.builder()
                    .tableName(questionsTableName)
                    .keyConditionExpression("exam_id = :examId")
                    .expressionAttributeValues(Map.of(":examId", AttributeValue.fromS(examId)))
                    .limit(pageSize);

            if (lastEvaluatedKey > -1) {
                builder.exclusiveStartKey(Map.of(
                        "exam_id", AttributeValue.builder().s(examId).build(),
                        "question_id", AttributeValue.builder().n(String.valueOf(lastEvaluatedKey)).build())
                );
            }

            QueryResponse resp = client.query(builder.build());

            GetQuestionsResponse getQuestionsResponse = new GetQuestionsResponse(
                    resp.items().stream().map(mapToQuestion()).toList(),
                    resp.lastEvaluatedKey().get("question_id").n()
            );

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withBody(gson.toJson(getQuestionsResponse))
                    .build();
        } catch (Exception e) {
            System.out.println(e.getMessage());

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withBody("Error getting questions")
                    .build();
        }
    }

    private APIGatewayV2HTTPResponse getBookmarks(APIGatewayV2HTTPEvent event) {
        try {
            Map<String, String> queryParams = event.getQueryStringParameters();
            String examId = queryParams.getOrDefault("examId", "aws-dva-c02");

            Map<String, String> claims = event.getRequestContext().getAuthorizer().getJwt().getClaims();
            String userId = claims.get("sub");

            String lastEvaluatedKey = queryParams.getOrDefault("lastEvaluatedKey", null);

            QueryRequest.Builder builder = QueryRequest.builder()
                    .tableName(bookmarkTableName)
                    .keyConditionExpression("user_id = :userId")
                    .expressionAttributeValues(Map.of(":userId", AttributeValue.fromS(userId)))
                    .projectionExpression("exam_question_key");

            if (lastEvaluatedKey != null) {
                builder.exclusiveStartKey(Map.of(
                        "user_id", AttributeValue.fromS(userId),
                        "exam_question_key", AttributeValue.builder().n(lastEvaluatedKey).build())
                );
            }

            QueryResponse resp = client.query(builder.build());
            GetBookmarksResponse getBookmarksResponse = new GetBookmarksResponse(
                    examId,
                    resp.items().stream()
                            .map(item -> Integer.parseInt(item.get("exam_question_key").s().split("#")[1]))
                            .toList());

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withBody(gson.toJson(getBookmarksResponse))
                    .build();
        } catch (Exception e) {
            System.out.println(e.getMessage());

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withBody("Error getting bookmarks")
                    .build();
        }
    }

    private APIGatewayV2HTTPResponse createBookmarks(APIGatewayV2HTTPEvent event) {
        try {

            String body = event.getBody();
            JsonObject jsonBody = gson.fromJson(body, JsonObject.class);
            String examId = jsonBody.get("examId").getAsString();
            String questionId = jsonBody.get("questionId").getAsString();

            Map<String, String> claims = event.getRequestContext().getAuthorizer().getJwt().getClaims();
            String userId = claims.get("sub");

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("user_dd", AttributeValue.fromS(userId));
            item.put("exam_question_key", AttributeValue.fromS(MessageFormat.format("{0}#{1}", examId, questionId)));
            item.put("created_at", AttributeValue.fromS(Instant.now().toString()));

            PutItemRequest request = PutItemRequest.builder()
                    .tableName(bookmarkTableName)
                    .item(item)
                    .build();

            client.putItem(request);

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(201)
                    .build();
        } catch (Exception e) {
            System.out.println(e.getMessage());

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withBody("Error creating bookmarks")
                    .build();
        }
    }

    private Function<Map<String, AttributeValue>, Question> mapToQuestion() {
        return item -> new Question(
                item.get("exam_id").s(),
                Integer.parseInt(item.get("question_id").n()),
                item.get("options").l().stream().map(AttributeValue::m).map(mapToOption()).toList(),
                item.get("question").s(),
                Optional.of(item.getOrDefault("s3_image_urls", AttributeValue.builder().build()).l()).orElse(Collections.emptyList()).stream().map(AttributeValue::s).toList()
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
