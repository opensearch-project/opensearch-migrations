# Generated by Django 5.1.3 on 2024-12-08 23:37

from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [
        ('transform_api', '0002_alter_transformation_id'),
    ]

    operations = [
        migrations.DeleteModel(
            name='Transformation',
        ),
    ]
