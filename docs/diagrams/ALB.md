## Application Load Balancer (ALB) Overview

The Application Load Balancer (ALB) is a key component in managing and distributing incoming application traffic across multiple targets, such as EC2 instances, in one or more Availability Zones. Below are detailed and simplified visual representations of the ALB architecture.

<details open>
    <summary>Simple ALB Architecture</summary>
    ```mermaid
    %% ALB-simple.mermaid

    flowchart TD
        subgraph Original
            direction LR
            A1[User Traffic] --> B1[Source Cluster]
            A1 -.-> MA1
            MA1 -.-> B1
            MA1 -.-> F1[Target Cluster]

            subgraph MA1[Migration Assistant]
            end
        end

        subgraph CaptureReplay
            direction LR
            A2[User Traffic] -.-> B2
            A2[User Traffic] --> MA2
            MA2 -->|Live Traffic| B2[Source Cluster]
            
            subgraph MA2[Migration Assistant]
            end
            MA2 -->|Saved Traffic| F2
        end

        subgraph Cutover
            direction LR
            A3[User Traffic] -.-> B3
            A3[User Traffic] --> MA3
            MA3 -.-> B3[Source Cluster]
            
            subgraph MA3[Migration Assistant]
            end
            MA3 --> F3
        end

        Original ~~~ CaptureReplay ~~~ Cutover

        subgraph Legend
            direction TB
            START[" "]:::hidden -->|Active| END1[" "]:::hidden
            START2[" "]:::hidden -.->|Inactive| END2[" "]:::hidden
        end

        linkStyle default stroke-width:2px,fill:none,stroke:black;

        linkStyle 0 stroke:green;
        linkStyle 1 stroke:red;
        linkStyle 2 stroke:red;
        linkStyle 3 stroke:red;

        linkStyle 4 stroke:red;
        linkStyle 5 stroke:green;
        linkStyle 6 stroke:green;
        linkStyle 7 stroke:green;

        linkStyle 8 stroke:red;
        linkStyle 9 stroke:green;
        linkStyle 10 stroke:red;
        linkStyle 11 stroke:green;

        linkStyle 12 stroke:hidden;
        linkStyle 13 stroke:hidden;

        linkStyle 14 stroke:green;
        linkStyle 15 stroke:red;
    ```
</details>

<details>
    <summary>Detailed ALB Architecture</summary>
    
    ```mermaid
    %% ALB-detailed.mermaid

    flowchart TD
        subgraph Original
            direction LR
            A1[User Traffic] --> B1[Source Cluster]
            A1 -.-> LB1
            LB1 -.-> SP1 -.-> B1
            CT1 -.-> F1

            F1[Target Cluster]

            subgraph MA1[Migration Assistant]
                direction LR
                LB1
                CT1[(CapturedTraffic)]
                SP1[SourceProxy]
                TP1[TargetProxy]
                LB1 -.-> TP1
            end
            SP1 -.-> CT1
            TP1 -.-> F1
        end

        subgraph CaptureReplay
            direction LR
            A2[User Traffic] -.-> B2
            A2[User Traffic] --> LB2
            LB2 --> SP2 -- Live Traffic --> B2[Source Cluster]
            CT2 --> F2[Target Cluster]
            
            subgraph MA2[Migration Assistant]
                direction LR
                LB2[LB]
                CT2[(CapturedTraffic)]
                SP2[SourceProxy]
                TP2[TargetProxy]
                LB2 -.-> TP2
            end
            SP2 -->|Saved Traffic| CT2
            TP2 -.-> F2
        end

        subgraph Cutover
            direction LR
            A3[User Traffic] -.-> B3
            A3[User Traffic] --> LB3
            LB3 -.-> SP3 -.-> B3[Source Cluster]
            CT3 -.-> F3[Target Cluster]
            
            subgraph MA3[Migration Assistant]
                direction LR
                LB3[LB]
                CT3[(CapturedTraffic)]
                SP3[SourceProxy]
                TP3[TargetProxy]
                LB3 --> TP3
            end
            SP3 -.-> CT3
            TP3 --> F3
        end

        Original ~~~ CaptureReplay ~~~ Cutover

        subgraph Legend
            direction TB
            START[" "]:::hidden -->|Active| END1[" "]:::hidden
            START2[" "]:::hidden -.->|Inactive| END2[" "]:::hidden
        end

        linkStyle default stroke-width:2px,fill:none,stroke:black;
        linkStyle 0 stroke:green;
        linkStyle 1 stroke:red;
        linkStyle 2 stroke:red;
        linkStyle 3 stroke:red;
        linkStyle 4 stroke:red;
        linkStyle 5 stroke:red;
        linkStyle 6 stroke:red;
        linkStyle 7 stroke:red;

        linkStyle 8 stroke:red;
        linkStyle 9 stroke:green;
        linkStyle 10 stroke:green;
        linkStyle 11 stroke:green;
        linkStyle 12 stroke:green;
        linkStyle 13 stroke:red;
        linkStyle 14 stroke:green;
        linkStyle 15 stroke:red;

        linkStyle 16 stroke:red;
        linkStyle 17 stroke:green;
        linkStyle 18 stroke:red;
        linkStyle 19 stroke:red;
        linkStyle 20 stroke:red;
        linkStyle 21 stroke:green;
        linkStyle 22 stroke:red;
        linkStyle 23 stroke:green;

        linkStyle 24 stroke:hidden;
        linkStyle 25 stroke:hidden;
        
        linkStyle 26 stroke:green;
        linkStyle 27 stroke:red;
    ```
</details>