from rest_framework import serializers
from console_link.models.migration import MigrationType


class DataProviderSerializer(serializers.Serializer):
    Host = serializers.CharField(max_length=255)
    Port = serializers.CharField(max_length=5)
    AuthType = serializers.ChoiceField(choices=['SIGV4', 'BASIC_AUTH', 'NO_AUTH'])
    SecretArn = serializers.CharField(max_length=255, required=False)


class OpenSearchIngestionCreateRequestSerializer(serializers.Serializer):
    PipelineRoleArn = serializers.CharField(max_length=255)
    PipelineName = serializers.CharField(max_length=28)
    IndexRegexSelections = serializers.ListField(
        child=serializers.CharField(max_length=50),
        required=False
    )
    Tags = serializers.ListField(
        child=serializers.CharField(max_length=50),
        required=False
    )
    LogGroupName = serializers.CharField(max_length=255, required=False)
    SourceDataProvider = DataProviderSerializer()
    TargetDataProvider = DataProviderSerializer()
    VpcSubnetIds = serializers.ListField(
        child=serializers.CharField(max_length=50)
    )
    VpcSecurityGroupIds = serializers.ListField(
        child=serializers.CharField(max_length=50)
    )


class OpenSearchIngestionStartRequestSerializer(serializers.Serializer):
    PipelineName = serializers.CharField(max_length=28)


class OpenSearchIngestionStopRequestSerializer(serializers.Serializer):
    PipelineName = serializers.CharField(max_length=28)


class GenericMigrationSerializer(serializers.Serializer):
    MigrationType = serializers.ChoiceField(choices=[mtype.value for mtype in MigrationType])
