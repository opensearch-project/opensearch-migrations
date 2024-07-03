from console_link.models.cluster import AuthMethod
from rest_framework import serializers


class DataProviderSerializer(serializers.Serializer):
    Host = serializers.CharField(max_length=2048)
    Port = serializers.CharField(max_length=5)
    AuthType = serializers.ChoiceField(choices=[e.name.upper() for e in AuthMethod])
    SecretArn = serializers.CharField(max_length=2048, required=False)


class OpenSearchIngestionCreateRequestSerializer(serializers.Serializer):
    PipelineRoleArn = serializers.CharField(max_length=2048)
    PipelineName = serializers.CharField(max_length=28)
    AwsRegion = serializers.CharField(max_length=28)
    IndexRegexSelections = serializers.ListField(
        child=serializers.CharField(max_length=2048),
        required=False
    )
    Tags = serializers.ListField(
        child=serializers.CharField(max_length=2048),
        required=False
    )
    LogGroupName = serializers.CharField(max_length=2048, required=False)
    SourceDataProvider = DataProviderSerializer()
    TargetDataProvider = DataProviderSerializer()
    VpcSubnetIds = serializers.ListField(
        child=serializers.CharField(min_length=15, max_length=24)
    )
    VpcSecurityGroupIds = serializers.ListField(
        child=serializers.CharField(min_length=11, max_length=20)
    )
