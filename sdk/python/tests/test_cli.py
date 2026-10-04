import os

from prayog_sdk.cli import load_dotenv


def test_dotenv_fills_missing_variables_only(tmp_path, monkeypatch):
    env = tmp_path / ".env"
    env.write_text("# comment\nPRAYOG_TEST_A=from-file\nPRAYOG_TEST_B=from-file\n\nnot a pair\n")
    monkeypatch.setenv("PRAYOG_TEST_B", "from-env")
    monkeypatch.delenv("PRAYOG_TEST_A", raising=False)

    load_dotenv(env)

    assert os.environ["PRAYOG_TEST_A"] == "from-file"
    assert os.environ["PRAYOG_TEST_B"] == "from-env"
