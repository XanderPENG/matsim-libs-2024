# TODO
This file lists tasks that need to be done in the freight collaboration project.

## allocation

- [X] Implement the freight p-sim code in the ``FreightPseudoSimulator``
  - [X] Deep copy ``freightCollaborator``
  - [X] Identify non-collaborating members and reset their plans to original plans
  - [ ] <del>How to create MATSim `Event` by simulating the new plans</del>
  - [X] Activity-based psim for carriers
  - [X] PSim Scorer to score the psim results

- [ ] Implement the specific allocation models
  - [ ] Proportional model
  - [ ] Marginal contribution model
  - [X] Shapley value model

- [ ] Distribute the cost or cost savings to the players
  - [X] Currently, it will allocate the cost savings to each receiver at the iteration ends (AllocationToScoreListener).
  
- [ ] Implement test examples, using the freight chessboard scenario
  - [ ] Create a simple chessboard scenario with several carriers and receivers
  - [X] Create own carrier scoring function factory, considering the income/charges from receivers
  - [X] Similarly but more complex, create receiver scoring function factory -> 
    add a BasicScoring function that can lead to cost when the receivers relax their time windows/service times.
    (this needs to be bonded (using `addOveridingModule`) before the simulation starts.)
  - [ ] Test the allocation models on this scenario
  - [ ] Analyze the results and validate the allocation models

One thing need to be considered here is: since there will be several mutable coalitions, where receivers could be in different coalitions,
   which means these receivers could be distributed cost multiple times (from different coalitions/carriers),
   Is this acceptable? Or should we change the allocation model to consider
1. only one coalition for each receiver
2. only allocate cost for the grand coalition
3. change the allocation mechanism to have an upper limit for each receiver, i.e., the cost without collaboration.
4. (My preferred way) instead of distributing carrier cost to receivers, we distribute cost savings to receivers
    This way, even if a receiver is in multiple coalitions, the total cost saving it receives will not exceed its cost saving without collaboration.
    Also, for all receivers, they are also charged based on their goods' volume/weight but only some returns got owing to the collaboration, which is more fair.
    However, this may lead to all receivers tending to join more coalitions to get more returns, which may not be realistic.
    To avoid this, we may set a penalty for receivers collaboration behavior, e.g., if a receiver collaborate by relaxing time window,
    he/she may get some inconvenience cost, which should be considered as an additional cost when scoring the plan. 
