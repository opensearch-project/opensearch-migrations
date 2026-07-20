import logging

from console_link.workflow.commands.secret_utils import _notify_existing_secrets


class TestNotifyExistingSecrets:
    def test_non_interactive_logs_only_count_not_names(self, caplog):
        existing = ["cluster-a-basic-creds", "cluster-b-basic-creds"]
        with caplog.at_level(logging.INFO):
            _notify_existing_secrets(existing, interactive=False)

        records = [r.getMessage() for r in caplog.records]
        assert any("2 existing secret(s)" in m for m in records)
        # The security fix: secret names must never reach the (server) logs.
        for name in existing:
            assert all(name not in m for m in records)

    def test_interactive_echoes_names(self, capsys):
        existing = ["cluster-a-basic-creds"]
        _notify_existing_secrets(existing, interactive=True)

        out = capsys.readouterr().out
        assert "cluster-a-basic-creds" in out

    def test_empty_existing_logs_nothing(self, caplog):
        with caplog.at_level(logging.INFO):
            _notify_existing_secrets([], interactive=False)

        assert caplog.records == []
