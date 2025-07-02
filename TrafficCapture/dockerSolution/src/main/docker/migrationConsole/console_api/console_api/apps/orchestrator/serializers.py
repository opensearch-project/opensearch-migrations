from rest_framework import serializers
from console_link.models.cluster import AuthMethod


class DataProviderSerializer(serializers.Serializer):
    Uri = serializers.URLField(max_length=2000)
    AuthType = serializers.ChoiceField(choices=[e.name.upper() for e in AuthMethod])
    SecretArn = serializers.CharField(max_length=2048, required=False)


class OpenSearchIngestionCreateRequestSerializer(serializers.Serializer):
    PipelineRoleArn = serializers.CharField(max_length=2048)
    PipelineManagerAssumeRoleArn = serializers.CharField(max_length=2048, required=False)
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


class OpenSearchIngestionDefaultRequestSerializer(serializers.Serializer):
    PipelineName = serializers.CharField(max_length=28)
    PipelineManagerAssumeRoleArn = serializers.CharField(max_length=2048, required=False)
