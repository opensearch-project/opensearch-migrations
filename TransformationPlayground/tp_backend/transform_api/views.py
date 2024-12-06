import logging

from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status
from .serializers import TransformationRequestSerializer, TransformationSerializer
from .models import Transformation


import uuid

from langchain_core.messages import HumanMessage

from transform_expert.prompting import get_transform_index_prompt
from transform_expert.utils.transforms import invoke_transform_expert, TransformTask, llm_python_w_tools


logger = logging.getLogger("transform_api")

class TransformationView(APIView):
    def post(self, request):
        logger.info(f"Received transformation request: {request.data}")

        # Validate incoming data
        serializer = TransformationRequestSerializer(data=request.data)
        if not serializer.is_valid():
            logger.error(f"Invalid transformation request: {serializer.errors}")
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)
        
        input_shape = serializer.validated_data['input_shape']

        # Perform the transformation
        try:
            output_shape, transform = self.perform_transformation(input_shape)
            logger.info(f"Transformation successful")
            logger.debug(f"Transformation output:\n{output_shape}")
            logger.debug(f"Transformation logic:\n{transform}")
        except Exception as e:
            logger.error(f"Transformation failed: {str(e)}")
            return Response({'error': str(e)}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

        # Save the transformation record (optional)
        transformation = Transformation.objects.create(
            input_shape=input_shape,
            output_shape=output_shape,
            transform=transform
        )
        logger.info(f"Transformation record saved")

        # Serialize and return the response
        response_serializer = TransformationSerializer(transformation)
        return Response(response_serializer.data, status=status.HTTP_200_OK)

    def perform_transformation(self, input_shape):
        system_message = get_transform_index_prompt(
            "Elasticsearch 6.8",
            "OpenSearch 2.14",
            {
                "indexName": "test_index",
                "indexJson": input_shape
            }
        )
        turns = [
            system_message,
            HumanMessage(content="Please make the transform.")
        ]

        transform_id = str(uuid.uuid4())

        transform_task = TransformTask(
            transform_id=transform_id,
            context=turns,
            llm=llm_python_w_tools,
            transform=None
        )

        transform_result = invoke_transform_expert(transform_task)

        output_shape = {"transformed_key": "example_value"}  # Mocked output
        transform = transform_result.transform.to_file_format()
        return output_shape, transform
