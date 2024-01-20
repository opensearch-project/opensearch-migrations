from rest_framework.response import Response
from rest_framework.decorators import api_view
import subprocess

@api_view(['GET'])
def migration_status(request):

    # Use subprocess.Popen to run the command
    #process = subprocess.Popen(['/root/catIndices.sh'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    process = subprocess.Popen(['ls'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    output, errors = process.communicate()

    # Check for errors
    if process.returncode != 0:
        return Response({"errors": errors}, status=400)

    # Split the output into a list of files
    index_listing = output.split('\n')

    # Return the list of files in the response
    return Response({"Index Listing\n": index_listing})



from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework import status
from .serializers import CustomSerializer

class CustomAPIView(APIView):

    def get(self, request):
        # Example of how you might retrieve data. This part depends on your application logic.
        data = {'field1': 'Example Data', 'field2': 123}
        serializer = CustomSerializer(data=data)
        if serializer.is_valid():
            return Response(serializer.data)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

    def post(self, request):
        serializer = CustomSerializer(data=request.data)
        if serializer.is_valid():
            # Here, you would handle the validated data. For example, saving it, processing it, etc.
            # Since this serializer is not bound to a model, you need to manually handle the save.
            # For this example, let's just return the validated data.
            return Response(serializer.validated_data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)


