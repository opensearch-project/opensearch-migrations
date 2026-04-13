import random
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from data.generate_dataset import gen_product_id, gen_date, gen_review_body, CATEGORIES, MARKETPLACES, PRODUCTS


def make_doc(i, star):
    return {
        "id": f"R{i:08d}",
        "product_title": random.choice(PRODUCTS),
        "review_body": gen_review_body(star),
        "review_headline": "Test headline",
        "product_category": random.choice(CATEGORIES),
        "marketplace": random.choice(MARKETPLACES),
        "product_id": gen_product_id(),
        "star_rating": star,
        "helpful_votes": random.randint(0, 500),
        "total_votes": 0,
        "review_date": gen_date(),
    }


def test_deterministic_output_with_seed():
    random.seed(42)
    doc_a = make_doc(0, 3)
    random.seed(42)
    doc_b = make_doc(0, 3)
    assert doc_a == doc_b


def test_required_fields_present():
    random.seed(0)
    doc = make_doc(1, 4)
    required = {"id", "product_title", "review_body", "review_headline",
                "product_category", "marketplace", "product_id",
                "star_rating", "helpful_votes", "total_votes", "review_date"}
    assert required.issubset(doc.keys())


def test_star_rating_in_range():
    random.seed(0)
    for star in range(1, 6):
        doc = make_doc(star, star)
        assert 1 <= doc["star_rating"] <= 5


def test_product_id_format():
    random.seed(0)
    pid = gen_product_id()
    assert pid.startswith("B")
    assert len(pid) == 10


def test_review_body_non_empty():
    random.seed(0)
    for star in range(1, 6):
        body = gen_review_body(star)
        assert isinstance(body, str) and len(body) > 0
