## Flowchart

```mermaid
graph LR
    CCDNode[CCD]
    ServiceNode[Service]
    CCDDatabase[(CCD database)]

    CCDNode -- AboutToSubmit JSON blob --> ServiceNode
    CCDNode -- Submitted JSON blob --> ServiceNode
    CCDNode -. Save case data .-> CCDDatabase
```
