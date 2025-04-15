package revisionbuddy.models;

import java.util.List;

public record GetMetadataResponse (
    List<Integer> questionIds
) {}
