(ns cropsupport.advisor
  "CropSupportAdvisor -- the LLM/decision-maker that proposes operations.

  The advisor operates purely at the proposal level; the Governor
  (cropsupport.governor) independently validates all proposals against
  physical/safety/regulatory rules before any action is committed.

  In production, this is driven by langgraph-clj StateGraph with LLM chat
  turns. For testing, this is a pure function layer.")

;; In production deployment, this module provides the StateGraph state
;; machine definition and LLM binding. For this blueprint, it's a skeleton.
