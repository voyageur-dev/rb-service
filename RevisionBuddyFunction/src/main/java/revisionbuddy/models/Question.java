package revisionbuddy.models;

import java.util.List;

public record Question(
        String examId,
        int questionId,
        List<Option> options,
        String question,
        List<String> s3ImageUrls
) {
    public record Option(
            boolean isCorrect,
            String text,
            List<String> s3ImageUrls
    ) {}
}
