package revisionbuddy.models;

import java.util.List;

public record GetQuestionResponse(
        List<Question> data
) {}
