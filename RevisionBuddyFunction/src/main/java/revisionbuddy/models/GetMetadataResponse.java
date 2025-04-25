package revisionbuddy.models;

import java.util.List;

public record GetMetadataResponse (
        List<Metadata> data
) {
    public record Metadata (
            String examId,
            int count
    ) {}
}
