import logging
from typing import Any, Dict

from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status
from .serializers import TransformationRequestSerializer, TransformationSerializer
from .models import Transformation


import uuid

from langchain_core.messages import HumanMessage

from transform_expert.expert import get_expert, invoke_transform_expert
from transform_expert.parameters import SourceVersion, TargetVersion, InputShapeType, TransformLanguage
from transform_expert.utils.transforms import TransformTask


logger = logging.getLogger("transform_api")

class TransformationsView(APIView):
    def _perform_transformation(self, transform_id: str, input_shape: Dict[str, Any]) -> TransformTask:
        expert = get_expert(
            source_version=SourceVersion.ES_6_8,
            target_version=TargetVersion.OS_2_14,
            input_shape_type=InputShapeType.INDEX,
            transform_language=TransformLanguage.PYTHON
        )

        system_message = expert.system_prompt_factory(
            {
                "indexName": "test_index",
                "indexJson": input_shape
            }            
        )
        turns = [
            system_message,
            HumanMessage(content="Please make the transform.")
        ]

        transform_task = TransformTask(
            transform_id=transform_id,
            context=turns,
            transform=None
        )

        transform_result = invoke_transform_expert(expert, transform_task)

        return transform_result


    def post(self, request):
        logger.info(f"Received transformation request: {request.data}")

        # Validate incoming data
        serializer = TransformationRequestSerializer(data=request.data)
        if not serializer.is_valid():
            logger.error(f"Invalid transformation request: {serializer.errors}")
            return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)
        
        input_shape = serializer.validated_data['input_shape']
        transform_id = str(uuid.uuid4())

        # Perform the transformation
        try:
            transform_result = self._perform_transformation(transform_id, input_shape)
            logger.info(f"Transformation successful")
            logger.debug(f"Transformation output:\n{transform_result.output}")
            logger.debug(f"Transformation logic:\n{transform_result.transform.to_file_format()}")
        except Exception as e:
            logger.error(f"Transformation failed: {str(e)}")
            logger.exception(e)
            return Response({'error': str(e)}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

        # Save the transformation record (optional)
        transformation = Transformation.objects.create(
            id=transform_id,
            input_shape=input_shape,
            output_shape=transform_result.output,
            transform=transform_result.transform.to_file_format()
        )
        logger.info(f"Transformation record saved w/ ID: {transformation.id}")

        # Serialize and return the response
        response_serializer = TransformationSerializer(transformation)
        return Response(response_serializer.data, status=status.HTTP_200_OK)
