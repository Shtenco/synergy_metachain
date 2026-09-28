import importlib.util
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1]
PATH=ROOT/"runtime"/"trust_contracts.py"
spec=importlib.util.spec_from_file_location("trust_contracts",PATH)
mod=importlib.util.module_from_spec(spec);assert spec and spec.loader;spec.loader.exec_module(mod)

def payload():
    return {"source_event_id":"e","source_contract_id":"synergy.accounting.recognition/v1",
    "payload_sha256":"1"*64,"commitment_id":"c1","finality_status":"FINALIZED"}

def test_finalized_commitment_is_claimable_once():
    s=mod.CommitmentReplayState();r=s.consume(payload())
    assert r["claimable_once"] is True and r["accounting_authority"] is False
    with pytest.raises(ValueError): s.consume(payload())

def test_non_finalized_commitment_fails():
    p=payload();p["finality_status"]="PENDING"
    with pytest.raises(ValueError): mod.CommitmentReplayState().consume(p)
