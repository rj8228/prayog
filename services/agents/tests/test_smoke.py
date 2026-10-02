import prayog_agents
import prayog_sdk


def test_agents_can_import_sdk() -> None:
    assert prayog_agents.__version__ == prayog_sdk.__version__
