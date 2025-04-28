package revisionbuddy.models;

import java.util.List;
import java.util.Map;

public record GetBookmarksResponse(
   Map<String, List<Integer>> bookmarks
) {}
