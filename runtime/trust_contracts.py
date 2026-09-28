from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


UPSTREAM_CONTRACT_ID="synergy.trust.commitment/v1"


@dataclass
class CommitmentReplayState:
    consumed: set[str] = field(default_factory=set)

    def consume(self, payload: dict[str,Any]) -> dict[str,Any]:
        status=str(payload.get("finality_status","")).upper()
        if status!="FINALIZED":
            raise ValueError(f"bridge input must be FINALIZED, got {status}")
        commitment_id=str(payload.get("commitment_id",""))
        if not commitment_id:
            raise ValueError("commitment_id required")
        if commitment_id in self.consumed:
            raise ValueError("replayed trust commitment")
        digest=str(payload.get("payload_sha256","")).lower()
        if len(digest)!=64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError("invalid payload_sha256")
        source_event=str(payload.get("source_event_id",""))
        source_contract=str(payload.get("source_contract_id",""))
        if not source_event or not source_contract:
            raise ValueError("source identity required")
        self.consumed.add(commitment_id)
        return {
            "source_event_id":source_event,
            "source_contract_id":source_contract,
            "payload_sha256":digest,
            "commitment_id":commitment_id,
            "claimable_once":True,
            "accounting_authority":False,
        }
