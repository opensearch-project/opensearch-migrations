package example;

class Valid {
// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// LegacyOwner.submit(Request) -> NewOwner.submit(Request); trace block needle
// REBUILD-TRACE-END(G5,source)
// REBUILD-LIMBO-START(G5)
// The note between START and the delimiter is valid scaffolding.
/*
    String hidden = "hidden needle";
*/
// REBUILD-LIMBO-END(G5)
// REBUILD-TRACE(G5,target): LegacyOwner.submit(Request) -> NewOwner.submit(Request); inline trace needle
    String live = "live needle";
// REBUILD-LIMBO-START(PA2)
/*
    void hiddenMethod() {}
*/
// REBUILD-LIMBO-END(PA2)
}
