from krktransit_prediction.domain.stop_keys import stop_key


def test_key_lowercases_trims_spaces_and_collapses_whitespace():
    assert stop_key("  Rondo   Mogilskie ", "01") == "rondo mogilskie|01"
    assert stop_key("AGH / UR", "02") == "agh / ur|02"


def test_missing_description_becomes_an_empty_part():
    assert stop_key("Kombinat", None) == "kombinat|"
    assert stop_key(None, None) == "|"


def test_only_spaces_are_trimmed_before_collapsing_like_postgres_btrim():
    assert stop_key("\tPlac", "01") == " plac|01"
    assert stop_key("Plac Centralny", "01") == "plac centralny|01"
