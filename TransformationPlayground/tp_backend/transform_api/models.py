from django.db import models

class Transformation(models.Model):
    input_shape = models.JSONField()  # Stores the starting shape of the object to be transformed
    output_shape = models.JSONField()  # Stores the shape of the object after transformation
    transform = models.TextField()  # Stores the transformation logic used to convert the input shape to the output shape
    created_at = models.DateTimeField(auto_now_add=True)  # Timestamp for when the request was processed
