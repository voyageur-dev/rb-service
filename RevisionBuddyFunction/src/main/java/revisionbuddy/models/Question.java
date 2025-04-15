package revisionbuddy.models;

import java.util.List;

public record Question(
        String examId,
        int questionId,
        List<Option> options,
        String question,
        List<String> s3ImageUrls,
        String sourceUrl
) {
    public record Option(
            boolean isCorrect,
            String text,
            List<String> s3ImageUrls
    ) {}
}
