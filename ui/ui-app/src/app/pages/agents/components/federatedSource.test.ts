import { describe, expect, it } from "vitest";
import { describeSource, sourceLabel } from "./federatedSource";

describe("federatedSource", () => {
    it("explains a failure with its reason", () => {
        const source = { source: "eu-west", outcome: "failed", reason: "capacity_exceeded", count: 0, truncated: false } as any;

        expect(sourceLabel(source)).toBe("eu-west: capacity exceeded");
        expect(describeSource(source)).toBe("This registry did not call it: too many calls or searches were in "
            + "progress, or more registries are enabled than one search queries.");
    });

    it("explains a credential that could not be read", () => {
        const source = { source: "eu-west", outcome: "failed", reason: "credential_unavailable", count: 0, truncated: false } as any;

        expect(sourceLabel(source)).toBe("eu-west: credential unavailable");
        expect(describeSource(source)).toBe("The credential configured for it could not be read, so it was not called.");
    });

    it("explains a failure without a reason, which only administrators are given", () => {
        const source = { source: "peer-1", outcome: "failed", count: 0, truncated: false } as any;

        expect(sourceLabel(source)).toBe("peer-1: failed");
        expect(describeSource(source)).toBe("The registry could not be searched. Only an administrator is shown why.");
    });
});
