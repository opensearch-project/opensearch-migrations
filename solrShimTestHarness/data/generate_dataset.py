#!/usr/bin/env python3
"""
Generates a synthetic product review dataset with natural-language text.

Usage:
    python3 data/generate_dataset.py [--output data/dataset.json] [--max-docs 200000]
"""

import argparse
import json
import random
import string
from datetime import datetime, timedelta

CATEGORIES = [
    "Electronics", "Books", "Clothing", "Home & Kitchen", "Sports & Outdoors",
    "Toys & Games", "Health & Personal Care", "Automotive", "Garden & Outdoor",
    "Office Products", "Pet Supplies", "Musical Instruments", "Software",
    "Tools & Home Improvement", "Beauty", "Grocery", "Baby", "Industrial",
    "Arts & Crafts", "Cell Phones & Accessories"
]

MARKETPLACES = ["US", "UK", "DE", "FR", "JP", "CA", "IT", "ES", "IN", "AU"]

PRODUCTS = [
    "Wireless Bluetooth Headphones", "USB-C Charging Cable", "Portable Power Bank",
    "Stainless Steel Water Bottle", "Ergonomic Office Chair", "LED Desk Lamp",
    "Mechanical Gaming Keyboard", "Noise Cancelling Earbuds", "Smart Watch Band",
    "Laptop Stand Adjustable", "Screen Protector Tempered Glass", "Phone Case Slim Fit",
    "Wireless Mouse Rechargeable", "HDMI Cable High Speed", "Webcam HD 1080p",
    "Bluetooth Speaker Portable", "Car Phone Mount Magnetic", "Ring Light with Tripod",
    "External Hard Drive 1TB", "Memory Card 128GB", "Fitness Tracker Band",
    "Electric Toothbrush Sonic", "Air Purifier HEPA Filter", "Robot Vacuum Cleaner",
    "Instant Pot Pressure Cooker", "Cast Iron Skillet 12 Inch", "Yoga Mat Non Slip",
    "Running Shoes Lightweight", "Backpack Travel Waterproof", "Sunglasses Polarized UV",
    "Wireless Charger Fast Charging", "Smart Home Hub Voice Control", "Security Camera Indoor",
    "Portable Projector Mini", "Electric Kettle Temperature Control", "Standing Desk Converter",
    "Noise Machine White Sound", "Essential Oil Diffuser", "Resistance Bands Set",
    "Foam Roller Muscle Recovery", "Jump Rope Speed Training", "Dumbbell Set Adjustable",
    "Blender Smoothie Maker", "Coffee Grinder Burr Mill", "Toaster Oven Convection",
    "Vacuum Sealer Food Storage", "Ice Maker Countertop", "Bread Machine Automatic",
    "Sewing Machine Beginner", "Telescope Astronomy Starter"
]

POSITIVE_SENTENCES = [
    "I absolutely love this product and would highly recommend it to anyone looking for quality.",
    "This is exactly what I was looking for and it exceeded all my expectations.",
    "The build quality is outstanding and it feels very premium in hand.",
    "I have been using this for several months now and it still works perfectly.",
    "Great value for the price, you really cannot go wrong with this purchase.",
    "The shipping was fast and the packaging was excellent with no damage at all.",
    "I bought this as a gift and the recipient was thrilled with it.",
    "After comparing several similar products, this one clearly stands out as the best option.",
    "The customer service team was incredibly helpful when I had questions about setup.",
    "This product has made my daily routine so much easier and more efficient.",
    "I was skeptical at first but after using it for a week I am completely convinced.",
    "The design is sleek and modern, it looks great in any setting.",
    "Battery life is impressive, lasting much longer than advertised.",
    "Setup was straightforward and took less than five minutes out of the box.",
    "I have already recommended this to several friends and family members.",
    "The materials used are clearly high quality and built to last for years.",
    "This is my second purchase of this item because the first one was so good.",
    "Compared to the previous version, this is a significant improvement in every way.",
    "The performance is consistently reliable even after extended daily use.",
    "I appreciate the attention to detail in the design and functionality.",
]

NEGATIVE_SENTENCES = [
    "I am very disappointed with this product and would not recommend it to anyone.",
    "The quality is terrible and it broke within the first week of normal use.",
    "This is nothing like what was advertised and I feel completely misled.",
    "I have tried contacting customer support multiple times with no response at all.",
    "The product arrived damaged and the return process has been a nightmare.",
    "Save your money and look elsewhere because this is not worth the price.",
    "After just two weeks of use, the product stopped working entirely.",
    "The instructions were confusing and incomplete, making assembly very frustrating.",
    "I expected much better quality based on the reviews but was sorely disappointed.",
    "The materials feel cheap and flimsy, definitely not what I expected for this price.",
    "This product overheats after about thirty minutes of continuous use.",
    "The sizing is completely off from what the description states.",
    "I have owned similar products that cost half the price and performed much better.",
    "The noise level is unacceptable and much louder than what was described.",
    "It stopped charging after the first month and the warranty process is terrible.",
]

MIXED_SENTENCES = [
    "The product works as described but the build quality could be better overall.",
    "It does the job adequately but I would not say it is exceptional by any means.",
    "For the price point, it is a decent option but there are better alternatives available.",
    "Some features work great while others feel like they were an afterthought.",
    "The initial setup was a bit tricky but once configured it works reasonably well.",
    "I have mixed feelings about this purchase as it has both pros and cons.",
    "The core functionality is solid but the accessories that come with it are lacking.",
    "It meets the basic requirements but falls short of the premium experience I expected.",
    "Good enough for casual use but serious users should look at higher end options.",
    "The product itself is fine but the delivery experience was quite poor.",
]

DETAIL_SENTENCES = [
    "The weight is approximately two pounds which makes it very portable and easy to carry around.",
    "It comes with a one year manufacturer warranty which provides some peace of mind.",
    "The color options available include black, white, silver, and navy blue.",
    "Compatible with both iOS and Android devices which is a nice touch.",
    "The dimensions are compact enough to fit in most standard bags and backpacks.",
    "It features a USB-C port for fast charging and data transfer capabilities.",
    "The operating temperature range is suitable for both indoor and outdoor use.",
    "Includes a detailed user manual in multiple languages for international customers.",
    "The firmware can be updated via the companion app available on all major platforms.",
    "Made from recycled materials which is great for environmentally conscious consumers.",
    "The noise reduction technology works surprisingly well in busy office environments.",
    "Water resistance rating makes it suitable for use in light rain conditions.",
    "The adjustable settings allow you to customize the experience to your preferences.",
    "It integrates seamlessly with existing smart home ecosystems and voice assistants.",
    "The LED indicator lights clearly show the current status and battery level.",
]

HEADLINE_TEMPLATES = [
    "Great product, highly recommend",
    "Terrible quality, very disappointed",
    "Love it, exactly what I needed",
    "Not worth the money at all",
    "Best purchase I have made this year",
    "Worst product I have ever bought",
    "Exceeded my expectations completely",
    "Below average quality for the price",
    "Solid product with minor issues",
    "Perfect gift for anyone",
    "Do not waste your money on this",
    "Five stars, absolutely perfect",
    "One star, completely broken on arrival",
    "Good value for everyday use",
    "Premium quality at a fair price",
    "Disappointing experience overall",
    "Works great right out of the box",
    "Stopped working after two weeks",
    "Better than expected for the price",
    "Would buy again without hesitation",
    "Decent but nothing special",
    "Amazing quality and fast shipping",
    "Poor packaging caused damage",
    "Exactly as described in the listing",
    "Much smaller than I expected",
]


def gen_review_body(star_rating):
    """Generate a natural review body based on star rating."""
    if star_rating >= 4:
        sentences = random.sample(POSITIVE_SENTENCES, random.randint(3, 6))
        sentences += random.sample(DETAIL_SENTENCES, random.randint(1, 3))
    elif star_rating <= 2:
        sentences = random.sample(NEGATIVE_SENTENCES, random.randint(3, 5))
        sentences += random.sample(DETAIL_SENTENCES, random.randint(0, 2))
    else:
        sentences = random.sample(MIXED_SENTENCES, random.randint(2, 4))
        sentences += random.sample(DETAIL_SENTENCES, random.randint(1, 2))
        sentences += random.sample(POSITIVE_SENTENCES, random.randint(0, 1))

    random.shuffle(sentences)
    n_paragraphs = random.randint(1, 3)
    chunk_size = max(1, len(sentences) // n_paragraphs)
    paragraphs = []
    for i in range(0, len(sentences), chunk_size):
        paragraphs.append(" ".join(sentences[i:i + chunk_size]))
    return " ".join(paragraphs)


def gen_product_id():
    return "B" + "".join(random.choices(string.ascii_uppercase + string.digits, k=9))


def gen_date(start_year=2015, end_year=2024):
    start = datetime(start_year, 1, 1)
    delta = (datetime(end_year, 12, 31) - start).days
    dt = start + timedelta(days=random.randint(0, delta))
    return dt.strftime("%Y-%m-%dT00:00:00Z")


def main():
    parser = argparse.ArgumentParser(description="Generate synthetic product review dataset")
    parser.add_argument("--output", default="data/dataset.json", help="Output path")
    parser.add_argument("--max-docs", type=int, default=200000, help="Number of documents")
    args = parser.parse_args()

    random.seed(42)
    documents = []
    for i in range(args.max_docs):
        star = random.randint(1, 5)
        doc = {
            "id": f"R{i:08d}",
            "product_title": random.choice(PRODUCTS),
            "review_body": gen_review_body(star),
            "review_headline": random.choice(HEADLINE_TEMPLATES),
            "product_category": random.choice(CATEGORIES),
            "marketplace": random.choice(MARKETPLACES),
            "product_id": gen_product_id(),
            "star_rating": star,
            "helpful_votes": random.randint(0, 500),
            "total_votes": 0,
            "review_date": gen_date(),
            "verified_purchase": random.random() > 0.3,
            "vine": random.random() > 0.95,
        }
        doc["total_votes"] = doc["helpful_votes"] + random.randint(0, 50)
        documents.append(doc)

        if (i + 1) % 50000 == 0:
            print(f"  Generated {i + 1}/{args.max_docs} documents...")

    print(f"  Generated {len(documents)} documents total")

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(documents, f, ensure_ascii=False)

    print(f"  Written to {args.output}")


if __name__ == "__main__":
    main()
