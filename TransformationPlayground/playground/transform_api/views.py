import logging
import uuid

from django.views.decorators.csrf import csrf_exempt
from langchain_core.messages import HumanMessage
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status

from .serializers import TransformsIndexCreateRequestSerializer, TransformsIndexCreateResponseSerializer
from transform_expert.expert import get_expert, invoke_expert
from transform_expert.parameters import TransformType
from transform_expert.validation import test_target_connection, TestTargetInnaccessibleError, IndexTransformValidator, ValidationReport
from transform_expert.utils.rest_client import RESTClient, ConnectionDetails
from transform_expert.utils.opensearch_client import OpenSearchClient
from transform_expert.utils.transforms import TransformTask


logger = logging.getLogger("transform_api")


class TransformsIndexView(APIView):
    @csrf_exempt
    def post(self, request):
        logger.info(f"Received transformation request: {request.data}")

        # Validate incoming data
        requestSerializer = TransformsIndexCreateRequestSerializer(data=request.data)
        if not requestSerializer.is_valid():
            logger.error(f"Invalid transformation request: {requestSerializer.errors}")
            return Response(requestSerializer.errors, status=status.HTTP_400_BAD_REQUEST)

        transform_id = str(uuid.uuid4())

        # Perform the transformation
        try:
            transform_report = self._perform_transformation(transform_id, requestSerializer)
            logger.info(f"Transformation successful")
            logger.debug(f"Transformation output:\n{transform_report.task.output}")
            logger.debug(f"Transformation logic:\n{transform_report.task.transform.to_file_format()}")
        except TestTargetInnaccessibleError as e:
            logger.error(f"Target cluster is not accessible: {str(e)}")
            return Response({'error': str(e)}, status=status.HTTP_400_BAD_REQUEST)
        except Exception as e:
            logger.error(f"Transformation failed: {str(e)}")
            logger.exception(e)
            return Response({'error': str(e)}, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

        # Serialize and return the response
        response_serializer = TransformsIndexCreateResponseSerializer(data={
            "output_shape": transform_report.task.output,
            "transform_logic": transform_report.task.transform.to_file_format(),
            "validation_report": transform_report.report_entries,
            "validation_outcome": "PASSED" if transform_report.passed else "FAILED"
        })
        if not response_serializer.is_valid():
            logger.error(f"Invalid transformation response: {response_serializer.errors}")
            return Response(response_serializer.errors, status=status.HTTP_500_INTERNAL_SERVER_ERROR)
        
        return Response(response_serializer.data, status=status.HTTP_200_OK)
    
    def _perform_transformation(self, transform_id: str, request: TransformsIndexCreateRequestSerializer) -> ValidationReport:
        # Create a test client and confirm the target cluster is accessible if the connection details are provided
        if request.validated_data["test_target_url"]:
            test_connection = OpenSearchClient(
                RESTClient(
                    ConnectionDetails(base_url=request.validated_data["test_target_url"])
                )
            )
            test_target_connection(test_connection)
        else:
            test_connection = None

        # Get the expert and generate the transformation
        expert = get_expert(
            source_version=request.validated_data["source_version"],
            target_version=request.validated_data["target_version"],
            transform_type=TransformType.INDEX,
            transform_language=request.validated_data["transform_language"]
        )

        system_message = expert.system_prompt_factory(
            request.validated_data["input_shape"]           
        )
        turns = [
            system_message,
            HumanMessage(content="Please make the transform.")
        ]

        transform_task = TransformTask(
            transform_id=transform_id,
            input=request.validated_data["input_shape"],
            context=turns,
            transform=None
        )

        transform_result = invoke_expert(expert, transform_task)

        # Execute the transformation on the input and test it against the target cluster
        transform_test_report = IndexTransformValidator(transform_result, test_connection).validate()

        return transform_test_report
