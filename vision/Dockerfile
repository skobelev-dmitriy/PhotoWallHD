# Vision service for PhotoWall HD.
#   docker build -t photowall-vision .                          # classical backend, no model, small image
#   docker build --build-arg BACKEND=segformer -t photowall-vision .   # neural scene parsing (downloads torch + weights)
FROM python:3.12-slim

ARG BACKEND=classical
ENV BACKEND=${BACKEND} \
    MODEL_ID=nvidia/segformer-b2-finetuned-ade-512-512 \
    HF_HOME=/models \
    PYTHONUNBUFFERED=1

WORKDIR /srv
COPY requirements.txt requirements-segformer.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
 && if [ "$BACKEND" = "segformer" ]; then \
      pip install --no-cache-dir --extra-index-url https://download.pytorch.org/whl/cpu torch \
   && pip install --no-cache-dir -r requirements-segformer.txt \
   && python -c "import os; from transformers import AutoImageProcessor, SegformerForSemanticSegmentation as M; m=os.environ['MODEL_ID']; AutoImageProcessor.from_pretrained(m); M.from_pretrained(m)"; \
    fi

COPY app ./app
EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
