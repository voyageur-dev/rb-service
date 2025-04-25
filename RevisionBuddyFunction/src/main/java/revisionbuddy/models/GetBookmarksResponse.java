package revisionbuddy.models;

import java.util.List;

public record GetBookmarksResponse(
   String examId,
   List<Integer> questionIds
) {}
