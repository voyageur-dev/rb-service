package revisionbuddy.models;

import java.util.List;

public record GetQuestionsResponse(
        List<Question> data
) {}
